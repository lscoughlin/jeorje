package org.github.lscoughlin.jeorje.core.subagent;

import static org.github.lscoughlin.jeorje.core.subagent.SubagentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompletionStepsTest {
    @TempDir Path dir;

    private Rig rig(CompletionSteps steps) {
        return new Rig(dir, SubagentLimits.defaults().withAskTimeout(java.time.Duration.ofMillis(300)), steps, null,
            List.of(), a -> Decision.ALLOW);
    }

    @Test
    void defaultStepsDoNothing() throws Exception {
        var result = CompletionSteps.none().run(null);
        assertTrue(result.success());
        assertNull(result.branch());
        try (var rig = rig(null)) {
            rig.model("m", new Scripted(text(WORK_TEXT), text(VALID)));
            var id = rig.spawn("worker", "m");
            assertEquals(Completion.Outcome.COMPLETED, rig.await(id).outcome());
            assertEquals(List.of(AgentLifecycle.RUNNING, AgentLifecycle.COMPLETED), rig.states(id));
        }
    }

    @Test
    void stepsDriveSyncResolveAndMergeStates() throws Exception {
        var seen = new AtomicReference<CompletionSteps.Context>();
        CompletionSteps steps = context -> {
            seen.set(context);
            context.enter(AgentLifecycle.SYNCING);
            context.enter(AgentLifecycle.RESOLVING);
            context.resumeWork("Resolve the conflict in Parser.java.");
            context.enter(AgentLifecycle.SYNCING);
            context.enter(AgentLifecycle.MERGING);
            return CompletionSteps.Result.ok("branch jeorje/p/worker merged as abc123");
        };
        try (var rig = rig(steps)) {
            var llm = new Scripted(text(WORK_TEXT), text("Resolved the regex conflict."), text(VALID));
            rig.model("m", llm);
            var id = rig.runtime.spawn(rig.root, SpawnRequest.of("worker", "Fix Parser.java regex")
                .withModel("m").withIsolation("worktree"));
            var completion = rig.await(id);
            assertEquals(Completion.Outcome.COMPLETED, completion.outcome());
            assertEquals(List.of(AgentLifecycle.RUNNING, AgentLifecycle.SYNCING, AgentLifecycle.RESOLVING,
                AgentLifecycle.SYNCING, AgentLifecycle.MERGING, AgentLifecycle.COMPLETED), rig.states(id));
            assertEquals("worktree", seen.get().isolation());
            assertEquals("worker", seen.get().name());
            assertEquals(id, seen.get().agent());
            assertEquals(dir, seen.get().workingDirectory());
            assertTrue(llm.requestText(2).contains("Branch facts: branch jeorje/p/worker merged as abc123"),
                "the summary step gets the merge result");
            assertTrue(llm.requestText(2).contains("Resolve the conflict in Parser.java."),
                "the summary step sees the resolution turn");
            assertEquals(1, rig.store.chunks.stream().filter(c -> c.kind().equals("subagent_summary")).count());
        }
    }

    @Test
    void failedStepsGiveTheOutcomeFailed() throws Exception {
        CompletionSteps steps = context -> {
            context.enter(AgentLifecycle.SYNCING);
            return CompletionSteps.Result.failed("sync_abort: conflicts in Parser.java", "branch kept");
        };
        try (var rig = rig(steps)) {
            rig.model("m", new Scripted(text(WORK_TEXT)));
            var id = rig.spawn("worker", "m");
            var completion = rig.await(id);
            assertEquals(Completion.Outcome.FAILED, completion.outcome());
            assertEquals("sync_abort: conflicts in Parser.java", completion.reason());
            assertEquals(AgentLifecycle.FAILED, rig.runtime.info(id).orElseThrow().state());
            assertTrue(completion.summary().startsWith("Outcome: failed: sync_abort"), completion.summary());
            assertTrue(completion.summary().contains("branch kept"));
        }
    }

    @Test
    void throwingStepsGiveTheOutcomeFailed() throws Exception {
        try (var rig = rig(context -> {
            throw new IllegalStateException("git is gone");
        })) {
            rig.model("m", new Scripted(text(WORK_TEXT)));
            var completion = rig.await(rig.spawn("worker", "m"));
            assertEquals(Completion.Outcome.FAILED, completion.outcome());
            assertTrue(completion.reason().contains("git is gone"));
        }
    }

    @Test
    void anInternalWaitInTheStepsMakesTheAgentBusy() throws Exception {
        var inWait = new CountDownLatch(1);
        var leave = new CountDownLatch(1);
        CompletionSteps steps = context -> {
            context.enter(AgentLifecycle.SYNCING);
            context.enter(AgentLifecycle.MERGING);
            try (var wait = context.internalWait(null, "integration lock")) {
                assertNotNull(wait);
                inWait.countDown();
                leave.await(10, TimeUnit.SECONDS);
            }
            return CompletionSteps.Result.ok("merged");
        };
        try (var rig = rig(steps)) {
            rig.model("m", new Scripted(text(WORK_TEXT), text(VALID)));
            var id = rig.spawn("worker", "m");
            assertTrue(inWait.await(10, TimeUnit.SECONDS));
            var asker = new org.github.lscoughlin.jeorje.core.model.AgentId("asker");
            rig.runtime.registerRoot(asker, "asker", CancellationToken.none(), List.of(), a -> Decision.ALLOW,
                in -> { }, () -> 1);
            assertEquals(AgentLifecycle.MERGING, rig.runtime.info(id).orElseThrow().state());
            assertEquals(AskResult.BUSY, rig.runtime.ask(asker, "worker", "q?", CancellationToken.none()).reason());
            leave.countDown();
            assertEquals(Completion.Outcome.COMPLETED, rig.await(id).outcome());
        }
    }

    @Test
    void cancelDuringTheStepsGivesCancelled() throws Exception {
        var inSteps = new CountDownLatch(1);
        CompletionSteps steps = context -> {
            context.enter(AgentLifecycle.SYNCING);
            inSteps.countDown();
            while (!context.cancel().isCancelled()) Thread.sleep(5);
            return CompletionSteps.Result.ok("merged");
        };
        try (var rig = rig(steps)) {
            rig.model("m", new Scripted(text(WORK_TEXT)));
            var id = rig.spawn("worker", "m");
            assertTrue(inSteps.await(10, TimeUnit.SECONDS));
            rig.runtime.cancel("worker");
            var completion = rig.await(id);
            assertEquals(Completion.Outcome.CANCELLED, completion.outcome());
            assertEquals(AgentLifecycle.CANCELLED, rig.runtime.info(id).orElseThrow().state());
        }
    }
}
