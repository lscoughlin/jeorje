package org.github.lscoughlin.jeorje.core.subagent;

import static org.github.lscoughlin.jeorje.core.subagent.SubagentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.agent.AgentInput;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubagentTalkTest {
    @TempDir Path dir;

    private static final CancellationToken NO_CANCEL = CancellationToken.none();

    private Rig rig(SubagentLimits limits, org.github.lscoughlin.jeorje.core.port.Tool... tools) {
        return new Rig(dir, limits, null, null, List.of(tools), a -> Decision.ALLOW);
    }

    private static SubagentLimits limits() {
        return SubagentLimits.defaults().withAskTimeout(Duration.ofSeconds(5));
    }

    private AgentId peer(Rig rig, String name) {
        var id = new AgentId(name);
        rig.runtime.registerRoot(id, name, CancellationToken.none(), List.of(), a -> Decision.ALLOW,
            in -> rig.rootInput.add(in), () -> 1);
        return id;
    }

    @Test
    void questionReachesTheSubagentAtAStepBoundaryAndTheAnswerComesBack() throws Exception {
        var release = new CountDownLatch(1);
        var slow = tool("slow", false, (in, ctx) -> {
            release.await(10, TimeUnit.SECONDS);
            return ToolResultData.ok("slow done");
        });
        try (var rig = rig(limits().withMaxAsks(5), slow)) {
            var answerer = tool("answerq", false, (in, ctx) -> {
                var r = rig.runtime.answer(ctx.agent(), "q1", "four");
                return ToolResultData.ok(r.ok() ? r.answer() : r.reason());
            });
            rig.runtime.addSharedTools(List.of(answerer));
            var llm = new Scripted(call("c1", "slow", "{}"), call("c2", "answerq", "{}"), text(WORK_TEXT), text(VALID));
            rig.model("m", llm);
            var id = rig.runtime.spawn(rig.root, SpawnRequest.of("worker", "Fix Parser.java regex")
                .withModel("m").withStoryId("S7"));
            waitUntil(() -> llm.calls() == 1);
            var asked = CompletableFuture.supplyAsync(() -> rig.runtime.ask(rig.root, "S7", "what is 2+2", NO_CANCEL));
            waitUntil(() -> rig.runtime.info(rig.root).orElseThrow().state() == AgentLifecycle.WAITING_AGENT);
            assertEquals("worker", rig.runtime.info(rig.root).orElseThrow().waitingFor());
            Thread.sleep(100);
            release.countDown();
            var result = asked.get(10, TimeUnit.SECONDS);
            assertTrue(result.ok(), String.valueOf(result.reason()));
            assertEquals("four", result.answer());
            assertEquals(AgentLifecycle.RUNNING, rig.runtime.info(rig.root).orElseThrow().state());
            rig.await(id);
            var text = llm.requestText(1);
            assertFalse(text.contains("[inbox]"), "the tool result has no inbox text");
            assertTrue(text.contains("[question from root (root), id q1]\nwhat is 2+2"), text);
        }
    }

    @Test
    void messageIsDeliveredAtTheStepBoundary() throws Exception {
        var release = new CountDownLatch(1);
        var slow = tool("slow", false, (in, ctx) -> {
            release.await(10, TimeUnit.SECONDS);
            return ToolResultData.ok("slow done");
        });
        try (var rig = rig(limits(), slow)) {
            var llm = new Scripted(call("c1", "slow", "{}"), text(WORK_TEXT), text(VALID));
            rig.model("m", llm);
            var id = rig.spawn("worker", "m");
            waitUntil(() -> llm.calls() == 1);
            var sent = rig.runtime.message(rig.root, "worker", "please hurry");
            assertTrue(sent.ok());
            assertEquals(1, llm.calls(), "the runtime does not interrupt a running tool");
            release.countDown();
            rig.await(id);
            var request = llm.requests.get(1);
            var last = request.messages().getLast();
            assertEquals(org.github.lscoughlin.jeorje.core.model.Role.USER, last.role());
            assertEquals("slow done", ((org.github.lscoughlin.jeorje.core.model.ToolResult) last.content().getFirst())
                .output(), "the tool result is unchanged");
            assertFalse(last.content().getFirst().toString().contains("please hurry"));
            assertTrue(last.content().getLast().toString().contains("please hurry"), "the next model call has it");
            assertTrue(llm.requestText(1).contains("[message from root (root)]\nplease hurry"), llm.requestText(1));
        }
    }

    @Test
    void messageToARootAgentArrivesAsAPeerMessage() {
        try (var rig = rig(limits())) {
            var other = peer(rig, "other");
            var sent = rig.runtime.message(other, "root", "hello");
            assertTrue(sent.ok());
            var input = assertInstanceOf(AgentInput.PeerMessage.class, rig.rootInput.getFirst());
            assertEquals(other, input.from());
            assertTrue(input.text().contains("hello"));
        }
    }

    // ---- ask failures ----

    @Test
    void deadlockOfTwoAgentsIsFound() throws Exception {
        try (var rig = rig(limits())) {
            var a = peer(rig, "a");
            var b = peer(rig, "b");
            var first = CompletableFuture.supplyAsync(() -> rig.runtime.ask(a, "b", "q?", NO_CANCEL));
            waitUntil(() -> rig.runtime.info(a).orElseThrow().state() == AgentLifecycle.WAITING_AGENT);
            var second = rig.runtime.ask(b, "a", "q?", NO_CANCEL);
            assertEquals(AskResult.DEADLOCK, second.reason());
            assertTrue(rig.runtime.answer(b, "q1", "fine").ok());
            assertEquals("fine", first.get(5, TimeUnit.SECONDS).answer());
        }
    }

    @Test
    void deadlockOfThreeAgentsIsFound() throws Exception {
        try (var rig = rig(limits())) {
            var a = peer(rig, "a");
            var b = peer(rig, "b");
            var c = peer(rig, "c");
            var f1 = CompletableFuture.supplyAsync(() -> rig.runtime.ask(a, "b", "q?", NO_CANCEL));
            waitUntil(() -> rig.runtime.info(a).orElseThrow().state() == AgentLifecycle.WAITING_AGENT);
            var f2 = CompletableFuture.supplyAsync(() -> rig.runtime.ask(b, "c", "q?", NO_CANCEL));
            waitUntil(() -> rig.runtime.info(b).orElseThrow().state() == AgentLifecycle.WAITING_AGENT);
            assertEquals(AskResult.DEADLOCK, rig.runtime.ask(c, "a", "q?", NO_CANCEL).reason());
            assertTrue(rig.runtime.answer(c, "q2", "x").ok());
            assertTrue(rig.runtime.answer(b, "q1", "y").ok());
            assertEquals("x", f2.get(5, TimeUnit.SECONDS).answer());
            assertEquals("y", f1.get(5, TimeUnit.SECONDS).answer());
        }
    }

    @Test
    void anInternalWaitCountsAsAnEdgeInTheDeadlockCheck() throws Exception {
        try (var rig = rig(limits())) {
            var a = peer(rig, "a");
            var b = peer(rig, "b");
            // a waits for a lock that b holds.
            try (var wait = rig.runtime.beginInternalWait(a, b, "integration lock")) {
                assertNotNull(wait);
                assertEquals(AskResult.DEADLOCK, rig.runtime.ask(b, "a", "q?", NO_CANCEL).reason());
            }
        }
    }

    @Test
    void anAgentInAnInternalWaitIsBusy() throws Exception {
        try (var rig = rig(limits().withAskTimeout(Duration.ofMillis(100)))) {
            var a = peer(rig, "a");
            var b = peer(rig, "b");
            try (var wait = rig.runtime.beginInternalWait(b, null, "waiting for a lock")) {
                assertNotNull(wait);
                var started = System.nanoTime();
                assertEquals(AskResult.BUSY, rig.runtime.ask(a, "b", "q?", NO_CANCEL).reason());
                assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(90), "busy returns at once");
                assertEquals(AgentLifecycle.RUNNING, rig.runtime.info(a).orElseThrow().state());
            }
            assertEquals(AskResult.TIMEOUT, rig.runtime.ask(a, "b", "q?", NO_CANCEL).reason(),
                "after the wait the agent is not busy");
        }
    }

    @Test
    void aParentInWaitModeIsInADeadlockWithItsChildAndBusyForOthers() throws Exception {
        try (var rig = rig(limits().withAskTimeout(Duration.ofMillis(200)))) {
            rig.model("h", new Scripted(hang(new CountDownLatch(1))));
            var child = rig.runtime.spawn(rig.root, SpawnRequest.of("child", "Work").withModel("h")
                .withMode(SpawnMode.WAIT));
            var sibling = peer(rig, "sibling");
            waitUntil(() -> rig.runtime.info(child).orElseThrow().state() == AgentLifecycle.RUNNING);
            assertEquals(AskResult.DEADLOCK, rig.runtime.ask(child, "root", "q?", NO_CANCEL).reason());
            assertEquals(AskResult.BUSY, rig.runtime.ask(sibling, "root", "q?", NO_CANCEL).reason());
            rig.runtime.cancel("child");
            rig.await(child);
            assertNotEquals(AskResult.BUSY, rig.runtime.ask(sibling, "root", "q?", NO_CANCEL).reason(),
                "the wait ends with the child");
        }
    }

    @Test
    void timeoutReturnsTheTimeoutReasonAndWithdrawsTheQuestion() {
        try (var rig = rig(limits().withAskTimeout(Duration.ofMillis(100)))) {
            var a = peer(rig, "a");
            var b = peer(rig, "b");
            var result = rig.runtime.ask(a, "b", "q?", NO_CANCEL);
            assertEquals(AskResult.TIMEOUT, result.reason());
            assertEquals(AgentLifecycle.RUNNING, rig.runtime.info(a).orElseThrow().state());
            assertFalse(rig.runtime.answer(b, "q1", "late").ok(), "the question is closed");
        }
    }

    @Test
    void anAgentHasOneOpenAsk() throws Exception {
        try (var rig = rig(limits())) {
            var a = peer(rig, "a");
            var b = peer(rig, "b");
            var c = peer(rig, "c");
            var first = CompletableFuture.supplyAsync(() -> rig.runtime.ask(a, "b", "q?", NO_CANCEL));
            waitUntil(() -> rig.runtime.info(a).orElseThrow().state() == AgentLifecycle.WAITING_AGENT);
            assertEquals(AskResult.OPEN_ASK, rig.runtime.ask(a, "c", "q?", NO_CANCEL).reason());
            assertTrue(rig.runtime.answer(b, "q1", "ok").ok());
            assertEquals("ok", first.get(5, TimeUnit.SECONDS).answer());
            assertNotNull(c);
        }
    }

    @Test
    void manyQuestionsCanWaitInOneInbox() throws Exception {
        try (var rig = rig(limits())) {
            var a = peer(rig, "a");
            var b = peer(rig, "b");
            var c = peer(rig, "c");
            var f1 = CompletableFuture.supplyAsync(() -> rig.runtime.ask(a, "c", "one?", NO_CANCEL));
            var f2 = CompletableFuture.supplyAsync(() -> rig.runtime.ask(b, "c", "two?", NO_CANCEL));
            waitUntil(() -> rig.runtime.info(a).orElseThrow().state() == AgentLifecycle.WAITING_AGENT
                && rig.runtime.info(b).orElseThrow().state() == AgentLifecycle.WAITING_AGENT);
            assertTrue(rig.runtime.answer(c, "q2", "second").ok(), "any order");
            assertTrue(rig.runtime.answer(c, "q1", "first").ok());
            assertEquals("first", f1.get(5, TimeUnit.SECONDS).answer());
            assertEquals("second", f2.get(5, TimeUnit.SECONDS).answer());
        }
    }

    @Test
    void askLimitStopsTheEleventhQuestion() {
        try (var rig = rig(limits().withAskTimeout(Duration.ofMillis(20)).withMaxAsks(10))) {
            var a = peer(rig, "a");
            peer(rig, "b");
            for (var i = 0; i < 10; i++) {
                assertEquals(AskResult.TIMEOUT, rig.runtime.ask(a, "b", "q" + i, NO_CANCEL).reason());
            }
            assertEquals(AskResult.LIMIT, rig.runtime.ask(a, "b", "q10", NO_CANCEL).reason());
        }
    }

    @Test
    void aTargetThatDidNotStartGivesNotStarted() {
        try (var rig = rig(limits())) {
            var a = peer(rig, "a");
            rig.runtime.registerPending("S9");
            assertEquals(AskResult.NOT_STARTED, rig.runtime.ask(a, "S9", "q?", NO_CANCEL).reason());
            assertEquals(AskResult.NOT_STARTED, rig.runtime.message(a, "S9", "hi").reason());
            rig.runtime.clearPending("S9");
            assertEquals(AskResult.UNKNOWN, rig.runtime.ask(a, "S9", "q?", NO_CANCEL).reason());
            assertEquals("self", rig.runtime.ask(a, "a", "q?", NO_CANCEL).reason());
        }
    }

    @Test
    void aTargetThatEndedGivesEndedWithTheSummary() throws Exception {
        try (var rig = rig(limits())) {
            var a = peer(rig, "a");
            rig.model("m", new Scripted(text(WORK_TEXT), text(VALID)));
            var id = rig.spawn("worker", "m");
            rig.await(id);
            var result = rig.runtime.ask(a, "worker", "q?", NO_CANCEL);
            assertEquals(AskResult.ENDED, result.reason());
            assertEquals(VALID, result.summary());
            assertTrue(result.summary().contains("Keywords: Parser.java"));
            var message = rig.runtime.message(a, "worker", "hi");
            assertEquals(AskResult.ENDED, message.reason());
            assertEquals(VALID, message.summary());
        }
    }

    @Test
    void aTargetThatEndsWhileAskedEndsTheAsk() throws Exception {
        try (var rig = rig(limits())) {
            var a = peer(rig, "a");
            var release = new CountDownLatch(1);
            var slow = tool("slow", false, (in, ctx) -> {
                release.await(10, TimeUnit.SECONDS);
                return ToolResultData.ok("slow done");
            });
            rig.runtime.addSharedTools(List.of(slow));
            var llm = new Scripted(call("c1", "slow", "{}"), text(WORK_TEXT), text(VALID));
            rig.model("m", llm);
            var id = rig.spawn("worker", "m");
            waitUntil(() -> llm.calls() == 1);
            var asked = CompletableFuture.supplyAsync(() -> rig.runtime.ask(a, "worker", "q?", NO_CANCEL));
            waitUntil(() -> rig.runtime.info(a).orElseThrow().state() == AgentLifecycle.WAITING_AGENT);
            Thread.sleep(50);
            release.countDown();
            rig.await(id);
            var result = asked.get(10, TimeUnit.SECONDS);
            // The question reached the inbox at the boundary after slow, so the agent saw it but did not answer.
            assertEquals(AskResult.ENDED, result.reason());
            assertEquals(VALID, result.summary());
        }
    }

    @Test
    void cancelOfTheAskerEndsTheAsk() throws Exception {
        try (var rig = rig(limits())) {
            var a = peer(rig, "a");
            peer(rig, "b");
            var token = new CancellationToken();
            var asked = CompletableFuture.supplyAsync(() -> rig.runtime.ask(a, "b", "q?", token));
            waitUntil(() -> rig.runtime.info(a).orElseThrow().state() == AgentLifecycle.WAITING_AGENT);
            token.cancel();
            assertEquals(AskResult.CANCELLED, asked.get(5, TimeUnit.SECONDS).reason());
            assertEquals(AgentLifecycle.RUNNING, rig.runtime.info(a).orElseThrow().state());
        }
    }

    @Test
    void answerChecksTheQuestionAndTheAnswerer() throws Exception {
        try (var rig = rig(limits())) {
            var a = peer(rig, "a");
            var b = peer(rig, "b");
            var c = peer(rig, "c");
            assertFalse(rig.runtime.answer(b, "q99", "x").ok());
            var asked = CompletableFuture.supplyAsync(() -> rig.runtime.ask(a, "b", "q?", NO_CANCEL));
            waitUntil(() -> rig.runtime.info(a).orElseThrow().state() == AgentLifecycle.WAITING_AGENT);
            var wrong = rig.runtime.answer(c, "q1", "x");
            assertFalse(wrong.ok());
            assertTrue(wrong.reason().contains("not for you"));
            assertTrue(rig.runtime.answer(b, "q1", "right").ok());
            assertEquals("right", asked.get(5, TimeUnit.SECONDS).answer());
        }
    }

    @Test
    void statusListsAgentsWithStateAndStory() throws Exception {
        try (var rig = rig(limits())) {
            rig.model("h", new Scripted(hang(new CountDownLatch(1))));
            var id = rig.runtime.spawn(rig.root, SpawnRequest.of("worker", "Do the thing").withModel("h")
                .withStoryId("B10"));
            rig.runtime.setBranch(id, "jeorje/p/B10");
            waitUntil(() -> rig.runtime.info(id).orElseThrow().state() == AgentLifecycle.RUNNING);
            var info = rig.runtime.info(id).orElseThrow();
            assertEquals("B10", info.storyId());
            assertEquals("jeorje/p/B10", info.branch());
            assertEquals(rig.root, info.parent());
            assertEquals(2, rig.runtime.status().size());
            assertEquals(id, rig.runtime.find("B10").orElseThrow());
            assertEquals("worker", rig.runtime.nameOf(id.value()));
            assertEquals("unknown-id", rig.runtime.nameOf("unknown-id"));
        }
    }
}
