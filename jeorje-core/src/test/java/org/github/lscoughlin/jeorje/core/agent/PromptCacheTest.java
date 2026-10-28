package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.Turn;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The start of each request stays the same from one model call to the next, so the provider can cache it. */
class PromptCacheTest {
    @TempDir Path dir;

    @Test
    void theMessagesBeforeTheTrailingStateAreTheSameInTheNextRequest() {
        var n = new int[] {0};
        var todo = tool("todo", false, (in, ctx) -> ToolResultData.ok("Todo list:\n1. [ ] STEP-" + (++n[0])));
        var llm = new Scripted(calls("c1", "todo", "{}"), calls("c2", "todo", "{}"), calls("c3", "todo", "{}"),
            text("done"));
        var rig = new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir), todo);
        rig.agent.runTurn("plan it");

        var requests = llm.requests;
        assertEquals(4, requests.size());
        for (var i = 1; i < requests.size(); i++) {
            var before = requests.get(i - 1);
            var prefix = before.cachedPrefixMessages();
            assertTrue(prefix > 0, "the agent asks for the cache");
            assertEquals(before.messages().subList(0, prefix), requests.get(i).messages().subList(0, prefix),
                "request " + i);
            assertEquals(before.system(), requests.get(i).system(), "the system prompt stays the same");
            assertEquals(before.tools(), requests.get(i).tools(), "the tools stay the same");
        }
        var last = requests.getLast();
        var tail = ((Text) last.messages().getLast().content().getFirst()).text();
        assertTrue(tail.startsWith("[Current state]") && tail.contains("STEP-3"), tail);
        assertEquals(last.messages().size() - 1, last.cachedPrefixMessages(), "the trailing state is one message");
        assertFalse(requests.getFirst().messages().toString().contains("[Current state]"), "no state, no tail");
    }

    @Test
    void aRequestWithNoTrailingStateCachesAllMessages() {
        var builder = new ContextBuilder(100_000, 60, 1_000);
        var built = builder.build(new ContextBuilder.Input("sys", List.of(), List.of(), List.of(),
            List.of(Message.user("q")), List.of()));
        assertEquals(1, built.request().cachedPrefixMessages());
    }

    @Test
    void theTrailingStateComesAfterTheMessagesAndDoesNotChangeThePrefix() {
        var builder = new ContextBuilder(100_000, 60, 1_000);
        var current = List.of(Message.user("q"));
        var one = builder.build(new ContextBuilder.Input("sys", List.of("PIN"), List.of(), List.of(), current,
            List.of(), List.of("todo one"))).request();
        var two = builder.build(new ContextBuilder.Input("sys", List.of("PIN"), List.of(), List.of(), current,
            List.of(), List.of("todo two"))).request();
        assertEquals(2, one.messages().size());
        assertEquals(1, one.cachedPrefixMessages());
        assertEquals(one.messages().subList(0, 1), two.messages().subList(0, 1));
        assertNotEquals(one.messages().getLast(), two.messages().getLast());
    }

    @Test
    void whenTurnsMustLeaveTheyLeaveUntilThereIsHeadroom() {
        var builder = new ContextBuilder(1_000, 60, 100);
        // Without turn 1 the request is about 560 tokens. That fits the budget of 600, but not the target of 540.
        var turns = List.of(turn(1, "a".repeat(800)), turn(2, "b".repeat(800)), turn(3, "c".repeat(1200)));
        var built = builder.build(new ContextBuilder.Input("sys", List.of(), List.of(), turns,
            List.of(Message.user("now")), List.of()));
        assertFalse(built.overBudget());
        assertTrue(built.estimate() <= built.budget() * 90 / 100, built.estimate() + " of " + built.budget());
        assertEquals(List.of(1, 2), built.droppedTurnNumbers());
    }

    @Test
    void theReportShowsTheCacheNumbersOfTheProvider() {
        var llm = new Scripted(withUsage(text("one"), 1000, 10, 0, 900), withUsage(text("two"), 1100, 10, 900, 100));
        var rig = new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir));
        assertTrue(rig.agent.contextReport().render().contains("reported no usage yet"));
        rig.agent.runTurn("a");
        rig.agent.runTurn("b");
        var cache = rig.agent.contextReport().cache();
        assertEquals(new org.github.lscoughlin.jeorje.core.agent.ContextReport.Cache(2, 1100, 900, 100, 2100, 900, 1000),
            cache);
        assertEquals(82, cache.lastPercent());
        assertEquals(43, cache.totalPercent());
        var text = rig.agent.contextReport().render();
        assertTrue(text.contains("last call: 900 of 1,100 prompt tokens read (82%), 100 written"), text);
        assertTrue(text.contains("all 2 calls: 900 of 2,100 prompt tokens read (43%), 1,000 written"), text);
        assertTrue(text.endsWith("Turns that left the last request: none"), text);
    }

    private static Turn turn(int n, String text) {
        return new Turn(new AgentId("a"), n, List.of(Message.user(text), Message.assistant("ok")));
    }
}
