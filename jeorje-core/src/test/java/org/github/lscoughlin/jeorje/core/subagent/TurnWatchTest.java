package org.github.lscoughlin.jeorje.core.subagent;

import static org.github.lscoughlin.jeorje.core.subagent.SubagentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The turn watch: a subagent at 80% of its limit without progress asks the advisor for help. */
class TurnWatchTest {
    private static final int LIMIT = 10;
    private static final List<TodoItem> START = List.of(new TodoItem("Fix the parser", "in_progress"),
        new TodoItem("Run the tests", "pending"), new TodoItem("Old task", "completed"));

    @TempDir Path dir;

    /** A stub for the advisor and the todo lists. */
    private static final class Link implements AdvisorLink {
        final AtomicReference<List<TodoItem>> todos = new AtomicReference<>(START);
        final List<String> requests = new CopyOnWriteArrayList<>();
        final Consumer<AtomicReference<List<TodoItem>>> onAdvice;

        Link(Consumer<AtomicReference<List<TodoItem>>> onAdvice) { this.onAdvice = onAdvice; }

        @Override public List<TodoItem> todos(AgentId agent) { return todos.get(); }

        @Override
        public Optional<String> advise(AgentId agent, String request, CancellationToken cancel) {
            requests.add(request);
            onAdvice.accept(todos);
            return Optional.of("Try the other file.");
        }
    }

    private static Consumer<AtomicReference<List<TodoItem>>> advisorUpdatesTodos() {
        return todos -> todos.set(List.of(new TodoItem("Read the test log", "in_progress")));
    }

    private static Consumer<AtomicReference<List<TodoItem>>> advisorOnlyTalks() {
        return todos -> { };
    }

    /** The result: the completion, the recorded events, and the scripted model. */
    private record Run(Completion completion, List<AgentEvent> events, Scripted model, Link link) {
        long helpEvents() { return events.stream().filter(AgentEvent.HelpRequested.class::isInstance).count(); }
    }

    private Run run(Link link, int workBefore, String progressTool, int workAfter) throws Exception {
        var steps = new ArrayList<Step>();
        for (var i = 0; i < workBefore; i++) steps.add(call("w" + i, "work", "{\"n\":" + i + "}"));
        if (progressTool != null) steps.add(call("p", progressTool, "{}"));
        for (var i = 0; i < workAfter; i++) steps.add(call("v" + i, "work", "{\"n\":" + (100 + i) + "}"));
        steps.add(text(WORK_TEXT));
        steps.add(text(VALID));
        var model = new Scripted(steps.toArray(new Step[0]));
        var events = new CopyOnWriteArrayList<AgentEvent>();
        var tools = List.of(simple("work"),
            tool("progress", false, (in, ctx) -> {
                link.todos.set(List.of(new TodoItem("Fix the parser", "completed"),
                    new TodoItem("Run the tests", "in_progress")));
                return ToolResultData.ok("updated");
            }));
        var settings = AgentSettings.defaults("s1", dir, 100_000).withSleeper(d -> { });
        var runtime = SubagentRuntime.builder("s1", settings, m -> model)
            .store(new ListStore()).agentEvents(events::add)
            .limits(SubagentLimits.defaults().withMaxModelCalls(LIMIT))
            .advisorLink(link).build();
        try {
            var root = new AgentId("root");
            runtime.registerRoot(root, "root", new CancellationToken(), tools,
                a -> PermissionPolicy.Decision.ALLOW, in -> { }, () -> 1);
            var id = runtime.spawn(root, SpawnRequest.of("worker", "Fix the Parser.java bug in regex handling."));
            var done = runtime.await(id, CancellationToken.none());
            return new Run(done, events, model, link);
        } finally {
            runtime.close();
        }
    }

    // ---- the watch alone ----

    @Test
    void watchCountsCallsAndAsksAtEightyPercent() {
        var watch = new TurnWatch(10);
        watch.onTurn(START);
        for (var i = 0; i < 6; i++) {
            assertFalse(watch.onTurn(START));
            assertFalse(watch.shouldAskForHelp(), "turns " + watch.turns());
        }
        watch.onTurn(START);
        assertEquals(8, watch.turns());
        assertTrue(watch.shouldAskForHelp());
        watch.helpAsked();
        assertFalse(watch.shouldAskForHelp());
        watch.onTurn(START);
        assertFalse(watch.shouldAskForHelp());
    }

    @Test
    void progressAndAdvisorUpdateResetTheCountAndTheHelpFlag() {
        var watch = new TurnWatch(10);
        watch.onTurn(START);
        for (var i = 0; i < 8; i++) watch.onTurn(START);
        watch.helpAsked();
        assertTrue(watch.onTurn(List.of(new TodoItem("Fix the parser", "completed"))));
        assertEquals(0, watch.turns());
        for (var i = 0; i < 8; i++) watch.onTurn(List.of(new TodoItem("Fix the parser", "completed")));
        assertTrue(watch.shouldAskForHelp());
        watch.helpAsked();
        watch.onTodosChanged();
        assertEquals(0, watch.turns());
        for (var i = 0; i < 8; i++) watch.onTurn(List.of(new TodoItem("Fix the parser", "completed")));
        assertTrue(watch.shouldAskForHelp());
    }

    @Test
    void addedRemovedAndRewordedTasksAreProgress() {
        var watch = new TurnWatch(10);
        watch.onTurn(START);
        assertTrue(watch.onTurn(List.of(START.get(0), START.get(1), START.get(2), new TodoItem("New", "pending"))));
        assertTrue(watch.onTurn(List.of(START.get(0), START.get(1))));
        assertTrue(watch.onTurn(List.of(new TodoItem("Fix the parser well", "in_progress"), START.get(1))));
        assertFalse(watch.onTurn(List.of(new TodoItem("Fix the parser well", "in_progress"), START.get(1))));
    }

    // ---- the runtime ----

    @Test
    void noHelpBelowEightyPercent() throws Exception {
        var run = run(new Link(advisorOnlyTalks()), 6, null, 0);
        assertEquals(Completion.Outcome.COMPLETED, run.completion().outcome());
        assertTrue(run.link().requests.isEmpty());
        assertEquals(0, run.helpEvents());
    }

    @Test
    void oneHelpRequestAtEightyPercentAndNoSecondInTheSameWindow() throws Exception {
        var run = run(new Link(advisorOnlyTalks()), 9, null, 0);
        assertEquals(Completion.Outcome.COMPLETED, run.completion().outcome());
        assertEquals(1, run.link().requests.size());
        assertEquals(1, run.helpEvents());
        var request = run.link().requests.getFirst();
        assertTrue(request.contains("Fix the Parser.java bug in regex handling."), request);
        assertTrue(request.contains("Fix the parser"), request);
        assertTrue(request.contains("Run the tests"), request);
        assertFalse(request.contains("Old task"), request);
        assertTrue(request.contains("8 of 10"), request);
        var help = (AgentEvent.HelpRequested) run.events().stream().filter(AgentEvent.HelpRequested.class::isInstance)
            .findFirst().orElseThrow();
        assertEquals(8, help.turns());
        assertEquals(LIMIT, help.limit());
        assertTrue(run.model().requestText(9).contains("Try the other file."), "the subagent gets the advice");
    }

    @Test
    void anAdvisorTodoUpdateResetsTheTurnCount() throws Exception {
        // 13 model calls are more than the limit of 10. The subagent only gets this far because of the reset.
        var run = run(new Link(advisorUpdatesTodos()), 12, null, 0);
        assertEquals(Completion.Outcome.COMPLETED, run.completion().outcome(), run.completion().reason());
        assertEquals(1, run.link().requests.size());
        assertEquals(1, run.helpEvents());
        assertTrue(run.events().stream().noneMatch(e -> e instanceof AgentEvent.Error));
    }

    @Test
    void ownProgressResetsTheTurnCountAndNeverAsksForHelp() throws Exception {
        // 12 model calls are more than the limit of 10. The progress of the subagent gives it a new count.
        var run = run(new Link(advisorOnlyTalks()), 4, "progress", 6);
        assertEquals(Completion.Outcome.COMPLETED, run.completion().outcome(), run.completion().reason());
        assertTrue(run.link().requests.isEmpty());
        assertEquals(0, run.helpEvents());
    }

    @Test
    void withoutProgressTheStepLimitEndsTheSubagentAtOneHundredPercent() throws Exception {
        var run = run(new Link(advisorOnlyTalks()), 12, null, 0);
        assertEquals(Completion.Outcome.FAILED, run.completion().outcome());
        assertTrue(run.completion().reason().startsWith("Step limit reached"), run.completion().reason());
        assertEquals(1, run.link().requests.size());
    }
}
