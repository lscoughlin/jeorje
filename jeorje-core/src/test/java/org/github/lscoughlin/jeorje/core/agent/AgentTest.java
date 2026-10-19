package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.Content;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentTest {
    @TempDir Path dir;

    private Rig rig(Scripted llm, Decision decision, org.github.lscoughlin.jeorje.core.port.Tool... tools) {
        return new Rig(llm, policy(decision), Rig.settings(dir), tools);
    }

    private static List<ToolResult> results(Message m) {
        return m.content().stream().filter(ToolResult.class::isInstance).map(ToolResult.class::cast).toList();
    }

    private static void assertValidHistory(List<Message> messages) {
        var open = new ArrayList<String>();
        for (var m : messages) {
            for (var c : m.content()) {
                if (c instanceof ToolUse u) open.add(u.id());
                if (c instanceof ToolResult r) assertTrue(open.remove(r.toolUseId()), "result without call " + r.toolUseId());
            }
        }
        assertTrue(open.isEmpty(), "open tool calls: " + open);
    }

    @Test
    void plainAnswer() {
        var llm = new Scripted(text("Hel", "lo"));
        var rig = rig(llm, Decision.ALLOW);
        rig.agent.runTurn("hi");
        assertEquals(List.of("Hel", "lo"), rig.events(AgentEvent.TokenDelta.class).stream().map(AgentEvent.TokenDelta::text).toList());
        assertEquals(1, rig.events(AgentEvent.TurnFinished.class).size());
        var turn = rig.agent.turns().getFirst();
        assertEquals("a1:1", turn.id());
        assertEquals(2, turn.messages().size());
        assertEquals(Role.ASSISTANT, turn.messages().get(1).role());
        assertEquals(1, rig.transcript.size());
        assertFalse(rig.store.chunks.isEmpty());
        assertTrue(rig.store.chunks.getFirst().text().contains("hi"));
        assertTrue(llm.requests.getFirst().system().contains("Jeorje"));
    }

    @Test
    void toolCallResultReachesModel() {
        var tool = tool("echo", true, (in, ctx) -> ToolResultData.ok("echoed " + in.get("v").asString()));
        var llm = new Scripted(calls("t1", "echo", "{\"v\":\"x\"}"), text("done"));
        var rig = rig(llm, Decision.ALLOW, tool);
        rig.agent.runTurn("go");
        var second = llm.requests.get(1).messages();
        assertEquals("echoed x", results(second.getLast()).getFirst().output());
        assertEquals(1, rig.events(AgentEvent.ToolStarted.class).size());
        assertEquals(1, rig.events(AgentEvent.ToolFinished.class).size());
        assertValidHistory(rig.agent.turns().getFirst().messages());
    }

    @Test
    void readOnlyCallsRunInParallel() {
        var both = new CountDownLatch(2);
        var body = (Body) (in, ctx) -> {
            both.countDown();
            if (!both.await(5, TimeUnit.SECONDS)) return ToolResultData.error("not parallel");
            return ToolResultData.ok("ok");
        };
        var llm = new Scripted(calls("t1", "a", "{}", "t2", "b", "{}"), text("done"));
        var rig = rig(llm, Decision.ALLOW, tool("a", true, body), tool("b", true, body));
        rig.agent.runTurn("go");
        var rs = results(llm.requests.get(1).messages().getLast());
        assertEquals(List.of("t1", "t2"), rs.stream().map(ToolResult::toolUseId).toList());
        assertTrue(rs.stream().noneMatch(ToolResult::error));
    }

    @Test
    void writeCallsRunOneAfterTheOther() {
        var running = new AtomicInteger();
        var overlap = new AtomicInteger();
        var order = new ArrayList<String>();
        var body = (Body) (in, ctx) -> {
            if (running.incrementAndGet() > 1) overlap.incrementAndGet();
            synchronized (order) { order.add("x"); }
            Thread.sleep(30);
            running.decrementAndGet();
            return ToolResultData.ok("ok");
        };
        var llm = new Scripted(calls("t1", "w", "{}", "t2", "r", "{}"), text("done"));
        var rig = rig(llm, Decision.ALLOW, tool("w", false, body), tool("r", true, body));
        rig.agent.runTurn("go");
        assertEquals(0, overlap.get());
        assertEquals(2, order.size());
        var started = rig.events(AgentEvent.ToolStarted.class).stream().map(AgentEvent.ToolStarted::toolUseId).toList();
        assertEquals(List.of("t1", "t2"), started);
    }

    @Test
    void askWaitsForConfirmation() throws Exception {
        for (var approve : new boolean[] {true, false}) {
            var ran = new AtomicInteger();
            var llm = new Scripted(calls("t1", "w", "{}"), text("done"));
            var rig = rig(llm, Decision.ASK, tool("w", false, (in, ctx) -> {
                ran.incrementAndGet();
                return ToolResultData.ok("wrote");
            }));
            var thread = Thread.ofVirtual().start(() -> rig.agent.runTurn("go"));
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (rig.events(AgentEvent.ConfirmationRequested.class).isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            var request = rig.events(AgentEvent.ConfirmationRequested.class).getFirst();
            assertEquals("t1", request.toolUse().id());
            assertEquals(0, ran.get());
            rig.agent.confirm("t1", approve);
            thread.join(5_000);
            assertEquals(approve ? 1 : 0, ran.get());
            var result = results(llm.requests.get(1).messages().getLast()).getFirst();
            assertEquals(!approve, result.error());
        }
    }

    @Test
    void denyDoesNotRunTheTool() {
        var ran = new AtomicInteger();
        var llm = new Scripted(calls("t1", "w", "{}"), text("done"));
        var rig = rig(llm, Decision.DENY, tool("w", false, (in, ctx) -> {
            ran.incrementAndGet();
            return ToolResultData.ok("x");
        }));
        rig.agent.runTurn("go");
        assertEquals(0, ran.get());
        var result = results(llm.requests.get(1).messages().getLast()).getFirst();
        assertTrue(result.error());
        assertTrue(result.output().contains("denied"));
        assertTrue(rig.events(AgentEvent.ConfirmationRequested.class).isEmpty());
    }

    @Test
    void toolExceptionBecomesErrorResult() {
        var llm = new Scripted(calls("t1", "bad", "{}"), text("done"));
        var rig = rig(llm, Decision.ALLOW, tool("bad", true, (in, ctx) -> { throw new IllegalStateException("boom"); }));
        rig.agent.runTurn("go");
        var result = results(llm.requests.get(1).messages().getLast()).getFirst();
        assertTrue(result.error());
        assertEquals("boom", result.output());
        assertEquals(1, rig.events(AgentEvent.TurnFinished.class).size());
    }

    @Test
    void unknownToolBecomesErrorResult() {
        var llm = new Scripted(calls("t1", "nope", "{}"), text("done"));
        var rig = rig(llm, Decision.ALLOW);
        rig.agent.runTurn("go");
        assertTrue(results(llm.requests.get(1).messages().getLast()).getFirst().error());
    }

    @Test
    void stepLimitEndsTheTurn() {
        var steps = new Step[10];
        for (var i = 0; i < steps.length; i++) steps[i] = calls("t" + i, "r", "{}");
        var llm = new Scripted(steps);
        var settings = Rig.settings(dir).withStepLimit(3);
        var rig = new Rig(llm, policy(Decision.ALLOW), settings, tool("r", true, (in, ctx) -> ToolResultData.ok("ok")));
        rig.agent.runTurn("loop");
        assertEquals(3, llm.requests.size());
        assertTrue(rig.events(AgentEvent.Error.class).stream().anyMatch(e -> e.message().contains("Step limit")));
        assertEquals(1, rig.events(AgentEvent.TurnFinished.class).size());
        assertValidHistory(rig.agent.turns().getFirst().messages());
    }

    @Test
    void defaultStepLimitIs200() {
        assertEquals(200, Rig.settings(dir).stepLimit());
        assertEquals(200, AgentSettings.DEFAULT_STEP_LIMIT);
    }

    @Test
    void cancelInTheMiddleOfAStream() throws Exception {
        var emitted = new CountDownLatch(1);
        var hang = new Step.HangAfter(List.of(
            new org.github.lscoughlin.jeorje.core.model.LlmEvent.TextDelta("part"),
            new org.github.lscoughlin.jeorje.core.model.LlmEvent.ToolUseStart("t1", "r"),
            new org.github.lscoughlin.jeorje.core.model.LlmEvent.ToolUseInput("t1", "{\"a\":")), emitted);
        var llm = new Scripted(hang, text("again"));
        var rig = rig(llm, Decision.ALLOW, tool("r", true, (in, ctx) -> ToolResultData.ok("ok")));
        var thread = Thread.ofVirtual().start(() -> rig.agent.runTurn("go"));
        assertTrue(emitted.await(5, TimeUnit.SECONDS));
        rig.agent.cancel();
        thread.join(5_000);
        var messages = rig.agent.turns().getFirst().messages();
        assertEquals(3, messages.size());
        assertEquals("part", ((Text) messages.get(1).content().getFirst()).text());
        var result = results(messages.get(2)).getFirst();
        assertEquals("interrupted", result.output());
        assertValidHistory(messages);
        // the agent stays usable
        rig.agent.runTurn("next");
        assertEquals(2, rig.agent.turns().size());
        assertEquals(2, llm.requests.size());
    }

    @Test
    void cancelInTheMiddleOfATool() throws Exception {
        var started = new CountDownLatch(1);
        var blocking = tool("slow", true, (in, ctx) -> {
            started.countDown();
            var gone = new CountDownLatch(1);
            ctx.cancel().onCancel(gone::countDown);
            gone.await();
            throw new java.util.concurrent.CancellationException("cancelled");
        });
        var llm = new Scripted(calls("t1", "slow", "{}", "t2", "slow", "{}"), text("never"));
        var rig = rig(llm, Decision.ALLOW, blocking);
        var thread = Thread.ofVirtual().start(() -> rig.agent.runTurn("go"));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        rig.agent.cancel();
        thread.join(5_000);
        var messages = rig.agent.turns().getFirst().messages();
        assertEquals(3, messages.size());
        var rs = results(messages.get(2));
        assertEquals(2, rs.size());
        assertTrue(rs.stream().allMatch(r -> r.output().equals("interrupted") && r.error()));
        assertEquals(1, llm.requests.size());
        assertValidHistory(messages);
    }

    @Test
    void retriesServerErrorsWithBackoff() {
        var llm = new Scripted(fail(503), fail(429), text("ok"));
        var rig = rig(llm, Decision.ALLOW);
        rig.agent.runTurn("hi");
        assertEquals(3, llm.requests.size());
        assertEquals(2, rig.sleeps.size());
        assertTrue(rig.sleeps.get(0).compareTo(Duration.ofSeconds(1)) <= 0);
        assertTrue(rig.sleeps.get(1).compareTo(Duration.ofSeconds(2)) <= 0);
        assertTrue(rig.events(AgentEvent.Error.class).isEmpty());
    }

    @Test
    void retryAfterReplacesTheDelay() {
        var llm = new Scripted(new Step.Fail(429, Duration.ofSeconds(7)), text("ok"));
        var rig = rig(llm, Decision.ALLOW);
        rig.agent.runTurn("hi");
        assertEquals(List.of(Duration.ofSeconds(7)), rig.sleeps);
    }

    @Test
    void clientErrorsAreNotRetried() {
        var llm = new Scripted(fail(400), text("ok"));
        var rig = rig(llm, Decision.ALLOW);
        rig.agent.runTurn("hi");
        assertEquals(1, llm.requests.size());
        assertEquals(1, rig.events(AgentEvent.Error.class).size());
        assertEquals(1, rig.events(AgentEvent.TurnFinished.class).size());
        // usable afterwards
        rig.agent.runTurn("again");
        assertEquals(2, llm.requests.size());
    }

    @Test
    void retriesStopAfterTheLastAttempt() {
        var llm = new Scripted(fail(500), fail(500), fail(500), fail(500), fail(500), text("late"));
        var rig = rig(llm, Decision.ALLOW);
        rig.agent.runTurn("hi");
        assertEquals(5, llm.requests.size());
        assertEquals(4, rig.sleeps.size());
        assertEquals(1, rig.events(AgentEvent.Error.class).size());
    }

    @Test
    void queueThreadRunsInputsFromAllSources() throws Exception {
        var llm = new Scripted(text("one"), text("two"), text("three"));
        var rig = rig(llm, Decision.ALLOW);
        rig.agent.start();
        rig.agent.submit(new AgentInput.UserInput("u"));
        rig.agent.submit(new AgentInput.PeerMessage(new org.github.lscoughlin.jeorje.core.model.AgentId("p"), "hello"));
        rig.agent.submit(new AgentInput.SubagentResult(new org.github.lscoughlin.jeorje.core.model.AgentId("s"), "done"));
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (rig.events(AgentEvent.TurnFinished.class).size() < 3 && System.nanoTime() < deadline) Thread.sleep(5);
        rig.agent.close();
        assertEquals(3, rig.events(AgentEvent.TurnFinished.class).size());
        var texts = rig.agent.turns().stream().map(t -> ((Text) t.messages().getFirst().content().getFirst()).text()).toList();
        assertEquals("u", texts.get(0));
        assertTrue(texts.get(1).contains("message from agent p"));
        assertTrue(texts.get(2).contains("result of subagent s"));
    }

    @Test
    void usageRefinesTheEstimate() {
        var llm = new Scripted(withUsage(text("a"), 4000, 5), text("b"));
        var rig = rig(llm, Decision.ALLOW);
        rig.agent.runTurn("hi");
        assertNotEquals(1.0, rig.agent.contextBuilder().scale());
    }

    @Test
    void oldTurnsLeaveTheRecentWindow() {
        var steps = new Step[8];
        for (var i = 0; i < steps.length; i++) steps[i] = text("answer " + i);
        var llm = new Scripted(steps);
        var rig = rig(llm, Decision.ALLOW);
        for (var i = 0; i < 8; i++) rig.agent.runTurn("question " + i);
        var last = llm.requests.get(7).messages();
        var text = last.stream().flatMap(m -> m.content().stream()).filter(Text.class::isInstance)
            .map(c -> ((Text) c).text()).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(text.contains("[Earlier context]"));
        assertTrue(text.contains("question 0"), "older turns stay in the request as chunks");
        assertEquals(Role.USER, last.getFirst().role());
        assertTrue(last.getFirst().content().size() >= 2 && last.getFirst().content().getFirst() instanceof Content);
    }
}
