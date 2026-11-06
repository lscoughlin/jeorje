package org.github.lscoughlin.jeorje.core.subagent;

import static org.github.lscoughlin.jeorje.core.subagent.SubagentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The progress signals: a file change that makes a new workspace state resets the call budget of a subagent. */
class ProgressSignalsTest {
    private static final int LIMIT = 10;

    @TempDir Path dir;

    private static ToolResultData ok() { return ToolResultData.ok("ok"); }

    /** Plays one edit: sets the state, then finishes the call. */
    private static boolean edit(ProgressSignals signals, AtomicInteger state, int next, String id) {
        signals.toolStarted(id, "edit");
        state.set(next);
        return signals.toolFinished(id, ok());
    }

    private ProgressSignals signals(AtomicInteger state) {
        return new ProgressSignals(dir, d -> Optional.of("state-" + state.get()));
    }

    @Test
    void aNewWorkspaceStateIsProgress() {
        var state = new AtomicInteger();
        var signals = signals(state);
        signals.modelCall();
        assertTrue(edit(signals, state, 1, "a"));
    }

    @Test
    void editAndUndoIsNotProgress() {
        var state = new AtomicInteger();
        var signals = signals(state);
        signals.modelCall();
        assertTrue(edit(signals, state, 1, "a"));
        for (var i = 0; i < 30; i++) signals.modelCall();
        assertFalse(edit(signals, state, 0, "b"), "the undo goes back to a state that the subagent had");
        for (var i = 0; i < 30; i++) signals.modelCall();
        assertFalse(edit(signals, state, 1, "c"), "the same edit again is not new");
    }

    @Test
    void atMostOneSignalInTenModelCalls() {
        var state = new AtomicInteger();
        var signals = signals(state);
        signals.modelCall();
        assertTrue(edit(signals, state, 1, "a"));
        for (var i = 2; i < 10; i++) {
            signals.modelCall();
            assertFalse(edit(signals, state, i, "e" + i), "call " + i);
        }
        signals.modelCall();
        signals.modelCall();
        assertTrue(edit(signals, state, 100, "late"));
    }

    @Test
    void anotherBudgetResetStartsTheTenCallsAgain() {
        var state = new AtomicInteger();
        var signals = signals(state);
        signals.modelCall();
        signals.budgetReset();
        for (var i = 0; i < 5; i++) signals.modelCall();
        assertFalse(edit(signals, state, 1, "a"));
    }

    @Test
    void aFailedEditAndOtherToolsAreNotProgress() {
        var state = new AtomicInteger();
        var signals = signals(state);
        signals.modelCall();
        signals.toolStarted("x", "edit");
        state.set(5);
        assertFalse(signals.toolFinished("x", ToolResultData.error("no match")));
        signals.toolStarted("y", "bash");
        state.set(6);
        assertFalse(signals.toolFinished("y", ok()));
    }

    @Test
    void aWorkspaceWithoutStateCountsEachChangeAsNewButKeepsTheRateLimit() {
        var signals = new ProgressSignals(dir, d -> Optional.empty());
        signals.modelCall();
        signals.toolStarted("a", "write");
        assertTrue(signals.toolFinished("a", ok()));
        signals.modelCall();
        signals.toolStarted("b", "write");
        assertFalse(signals.toolFinished("b", ok()));
    }

    /** A tool "edit" that sets the state of the fake workspace. */
    private static Tool editTool(AtomicInteger state, java.util.function.IntUnaryOperator next) {
        return tool("edit", false, (in, ctx) -> {
            state.set(next.applyAsInt(state.get()));
            return ToolResultData.ok("edited");
        });
    }

    private Completion runWorker(List<Step> steps, Tool edit, AtomicInteger state) throws Exception {
        var model = new Scripted(steps.toArray(new Step[0]));
        var settings = AgentSettings.defaults("s1", dir, 100_000).withSleeper(d -> { });
        var runtime = SubagentRuntime.builder("s1", settings, m -> model)
            .store(new ListStore())
            .fingerprint(d -> Optional.of("state-" + state.get()))
            .limits(SubagentLimits.defaults().withMaxModelCalls(LIMIT)).build();
        try {
            var root = new AgentId("root");
            runtime.registerRoot(root, "root", new CancellationToken(), List.of(edit, simple("work")),
                a -> PermissionPolicy.Decision.ALLOW, in -> { }, () -> 1);
            var id = runtime.spawn(root, SpawnRequest.of("worker", "Change the code."));
            return runtime.await(id, CancellationToken.none());
        } finally {
            runtime.close();
        }
    }

    private static List<Step> calls(String tool, int count) {
        var steps = new ArrayList<Step>();
        for (var i = 0; i < count; i++) steps.add(call(tool + i, tool, "{\"n\":" + i + "}"));
        return steps;
    }

    @Test
    void editsThatChangeTheStateGiveANewBudgetSoThatTheWorkerPassesTheLimit() throws Exception {
        var state = new AtomicInteger();
        var steps = calls("edit", 25);
        steps.add(text(WORK_TEXT));
        steps.add(text(VALID));
        var done = runWorker(steps, editTool(state, s -> s + 1), state);
        assertEquals(Completion.Outcome.COMPLETED, done.outcome(), done.reason());
    }

    @Test
    void editAndUndoEndsWithTheStepLimitError() throws Exception {
        var state = new AtomicInteger();
        var steps = calls("edit", 25);
        steps.add(text(WORK_TEXT));
        steps.add(text(VALID));
        var done = runWorker(steps, editTool(state, s -> 1 - s), state);
        assertEquals(Completion.Outcome.FAILED, done.outcome());
        assertTrue(done.reason().toLowerCase().contains("limit"), done.reason());
    }

    @Test
    void aWorkerWithoutAnyProgressEndsWithTheStepLimitError() throws Exception {
        var state = new AtomicInteger();
        var steps = calls("work", 25);
        steps.add(text(WORK_TEXT));
        steps.add(text(VALID));
        var done = runWorker(steps, editTool(state, s -> s + 1), state);
        assertEquals(Completion.Outcome.FAILED, done.outcome());
        assertTrue(done.reason().toLowerCase().contains("limit"), done.reason());
    }
}
