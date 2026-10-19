package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Steering: a text of the user joins the running turn at its next step boundary. */
class AgentSteerTest {
    @TempDir Path dir;

    private Rig rig(Scripted llm, Tool... tools) {
        return new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir), tools);
    }

    /** A tool that tells the test when it runs, then waits for the test to release it. */
    private static final class Slow {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        Tool tool() {
            return AgentTestSupport.tool("slow", true, (in, ctx) -> {
                started.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS), "the test releases the tool");
                return ToolResultData.ok("slow output");
            });
        }
    }

    /** The texts of the user messages of a request, in order. */
    private static List<String> userTexts(LlmRequest request) {
        var out = new ArrayList<String>();
        for (var message : request.messages()) {
            if (message.role() != Role.USER) continue;
            for (var c : message.content()) {
                if (c instanceof Text t) out.add(t.text());
            }
        }
        return out;
    }

    /** The last user message of a request. */
    private static org.github.lscoughlin.jeorje.core.model.Message lastUser(LlmRequest request) {
        var messages = request.messages();
        for (var i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) return messages.get(i);
        }
        throw new AssertionError("no user message in " + messages);
    }

    private Thread runTurn(Agent agent, String text) {
        var thread = Thread.ofVirtual().start(() -> agent.runTurn(text));
        thread.setUncaughtExceptionHandler(null);
        return thread;
    }

    @Test
    void aSteerReachesTheModelBeforeTheNextCallAndTheTurnGoesOn() throws Exception {
        var slow = new Slow();
        var llm = new Scripted(calls("t1", "slow", "{}"), text("done"));
        var rig = rig(llm, slow.tool());

        var turn = runTurn(rig.agent, "go");
        assertTrue(slow.started.await(10, TimeUnit.SECONDS), "the tool runs");
        assertTrue(rig.agent.steer("use the offline profile"), "a running turn takes the text");
        slow.release.countDown();
        turn.join(10_000);
        assertFalse(turn.isAlive());

        var steered = rig.events(AgentEvent.Steered.class);
        assertEquals(1, steered.size());
        assertEquals("use the offline profile", steered.getFirst().text());
        assertTrue(steered.getFirst().running());

        var second = llm.requests.get(1);
        assertEquals(List.of("go", "use the offline profile"), userTexts(second),
                "the text joins the turn before the next model call");
        // The tool result stays in front of the text of the user, so the tool result follows its tool use.
        var last = lastUser(second);
        assertInstanceOf(ToolResult.class, last.content().getFirst(), last.content().toString());
        assertEquals("use the offline profile", ((Text) last.content().getLast()).text());

        // The turn continued to its own end: one finished turn, with the text of the user in its history.
        assertEquals(1, rig.agent.turns().size(), "the steer does not start a new turn");
        assertEquals(1, rig.events(AgentEvent.TurnFinished.class).size());
        assertTrue(userTexts(new LlmRequest("", rig.agent.turns().getFirst().messages(), List.of(), 100))
                .contains("use the offline profile"));
    }

    @Test
    void twoSteersInOneStepBecomeOneMessageInOrder() throws Exception {
        var slow = new Slow();
        var llm = new Scripted(calls("t1", "slow", "{}"), text("done"));
        var rig = rig(llm, slow.tool());

        var turn = runTurn(rig.agent, "go");
        assertTrue(slow.started.await(10, TimeUnit.SECONDS), "the tool runs");
        rig.agent.steer("first steer");
        rig.agent.steer("second steer");
        slow.release.countDown();
        turn.join(10_000);
        assertFalse(turn.isAlive());

        var second = llm.requests.get(1);
        assertEquals(List.of("go", "first steer", "second steer"), userTexts(second));
        var last = lastUser(second);
        assertEquals(3, last.content().size(), "one tool result and two texts");
    }

    @Test
    void aSteerStartsANewCountOfModelCalls() throws Exception {
        var slow = new Slow();
        var llm = new Scripted(calls("t1", "slow", "{}"), calls("t2", "slow", "{}"), text("done"));
        var settings = Rig.settings(dir).withStepLimit(2);
        var rig = new Rig(llm, policy(Decision.ALLOW), settings, slow.tool());

        var made = new java.util.concurrent.atomic.AtomicInteger(-1);
        var turn = Thread.ofVirtual().start(() -> made.set(rig.agent.runTurn("go", 2)));
        assertTrue(slow.started.await(10, TimeUnit.SECONDS), "the tool runs");
        assertTrue(rig.agent.steer("keep going"));
        slow.release.countDown();
        turn.join(10_000);
        assertFalse(turn.isAlive());

        // The step limit is 2. Without the steer the turn would end after the second call. The steer started a new
        // count, so the turn made a third call and ended on its own.
        assertEquals(3, llm.requests.size());
        assertTrue(rig.events(AgentEvent.Error.class).isEmpty(), "the step limit did not end the turn");
        assertEquals(1, rig.agent.turns().size());
        assertEquals(3, made.get(), "the result counts every model call of the turn");
    }

    @Test
    void theStepLimitStillEndsATurnThatTakesOneSteer() throws Exception {
        var slow = new Slow();
        // Three tool calls after the steer use the new budget of 2 calls to its end.
        var llm = new Scripted(calls("t1", "slow", "{}"), calls("t2", "slow", "{}"), calls("t3", "slow", "{}"));
        var settings = Rig.settings(dir).withStepLimit(2);
        var rig = new Rig(llm, policy(Decision.ALLOW), settings, slow.tool());

        var turn = runTurn(rig.agent, "go");
        assertTrue(slow.started.await(10, TimeUnit.SECONDS), "the tool runs");
        assertTrue(rig.agent.steer("keep going"));
        slow.release.countDown();
        turn.join(10_000);
        assertFalse(turn.isAlive());

        assertEquals(3, llm.requests.size());
        var errors = rig.events(AgentEvent.Error.class);
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().message().contains("Step limit"), errors.getFirst().message());
        assertEquals(1, rig.agent.turns().size());
    }

    @Test
    void aSteerWithNoTurnRunningIsRefusedAndNeverWaits() {
        var llm = new Scripted(text("done"), text("again"));
        var rig = rig(llm);
        rig.agent.runTurn("go");
        // With no turn, the caller starts a turn with the text. Nothing waits in the queue.
        assertFalse(rig.agent.steer("later"));
        assertTrue(rig.events(AgentEvent.Steered.class).isEmpty(), "a refused steer publishes no event");
        rig.agent.runTurn("next");
        var texts = userTexts(llm.requests.getLast());
        assertFalse(texts.contains("later"), "the refused text is not in the turn: " + texts);
        assertEquals("next", texts.getLast());
    }

    @Test
    void aTextThatArrivesAfterTheLastBoundaryRunsAsANewTurn() throws Exception {
        var inSecondCall = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var inner = new Scripted(calls("t1", "noop", "{}"), text("one"), text("two"));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        org.github.lscoughlin.jeorje.core.port.LlmClient llm = (request, cancel) -> {
            if (calls.incrementAndGet() == 2) {
                inSecondCall.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS), "the test releases the model call");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return inner.stream(request, cancel);
        };
        var rig = new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir),
                tool("noop", true, (in, ctx) -> ToolResultData.ok("ok")));

        var turn = runTurn(rig.agent, "go");
        assertTrue(inSecondCall.await(10, TimeUnit.SECONDS), "the model call runs");
        // The last step boundary of the turn has passed, so no step boundary takes this text.
        assertTrue(rig.agent.steer("also this"));
        release.countDown();
        turn.join(10_000);
        assertFalse(turn.isAlive());

        assertEquals(2, rig.agent.turns().size(), "the late text runs as a new turn");
        var texts = userTexts(inner.requests.getLast());
        assertTrue(texts.contains("also this"), texts.toString());
        assertEquals(2, rig.events(AgentEvent.TurnFinished.class).size(), "both turns finished");
    }

    @Test
    void turnActiveFollowsTheTurn() throws Exception {
        var slow = new Slow();
        var llm = new Scripted(calls("t1", "slow", "{}"), text("done"));
        var rig = rig(llm, slow.tool());
        assertFalse(rig.agent.turnActive(), "no turn before the run");
        var turn = runTurn(rig.agent, "go");
        assertTrue(slow.started.await(10, TimeUnit.SECONDS), "the tool runs");
        assertTrue(rig.agent.turnActive(), "the turn takes a steer");
        slow.release.countDown();
        turn.join(10_000);
        assertFalse(turn.isAlive());
        assertFalse(rig.agent.turnActive(), "the turn ended");
    }

    @Test
    void aBlankSteerDoesNothing() {
        var llm = new Scripted(text("done"));
        var rig = rig(llm);
        assertFalse(rig.agent.steer("   "));
        assertFalse(rig.agent.steer(null));
        assertTrue(rig.events(AgentEvent.Steered.class).isEmpty());
        rig.agent.runTurn("go");
        assertEquals(List.of("go"), userTexts(llm.requests.getLast()));
    }
}
