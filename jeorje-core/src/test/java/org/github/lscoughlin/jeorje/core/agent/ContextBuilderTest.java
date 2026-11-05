package org.github.lscoughlin.jeorje.core.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Turn;
import org.junit.jupiter.api.Test;

/** The two parts of the budget: the hard headroom for the request, and the soft working budget below it. */
class ContextBuilderTest {

    /** An input with no system prompt, so that the estimate is a plain function of the messages. */
    private static ContextBuilder.Input input(List<Turn> turns, List<Message> current) {
        return new ContextBuilder.Input("", List.of(), List.of(), turns, current, List.of());
    }

    private static Turn turn(int n, String text) {
        return new Turn(new AgentId("a"), n, List.of(Message.user(text), Message.assistant("ok")));
    }

    /**
     * A message of {@code chars} characters. Its estimate is {@code chars / 4 + 4}, so a multiple of four gives an
     * exact number of tokens.
     */
    private static Message message(int chars) {
        return Message.user("y".repeat(chars));
    }

    @Test
    void headroomIsTheWindowLessTheOutput() {
        var b = new ContextBuilder(10_000, 60, 2_000);
        assertEquals(8_000, b.headroom());
        assertEquals(4_800, b.softBudget(), "the soft budget is 60% of the headroom");
        assertEquals(4_800, b.budget(), "budget() is the soft working budget");
    }

    @Test
    void aWindowNoLargerThanTheOutputLeavesOneToken() {
        assertEquals(1, new ContextBuilder(1_000, 60, 1_000).headroom());
        assertEquals(1, new ContextBuilder(1_000, 60, 5_000).headroom(), "a negative room is clamped to one");
        assertEquals(1, new ContextBuilder(1_000, 60, 1_000).softBudget());
        assertEquals(1, new ContextBuilder(1_000, 60, 5_000).budget());
    }

    @Test
    void aWindowWithNoOutputUsesTheWholeWindow() {
        var b = new ContextBuilder(1_000, 60, 0);
        assertEquals(1_000, b.headroom());
        assertEquals(600, b.softBudget());
    }

    @Test
    void theBuiltRequestCarriesBothNumbers() {
        var built = new ContextBuilder(10_000, 60, 2_000).build(input(List.of(), List.of(message(40))));
        assertEquals(8_000, built.headroom());
        assertEquals(4_800, built.budget());
        assertFalse(built.overBudget());
    }

    @Test
    void aRequestExactlyAtTheHeadroomIsNotOverBudget() {
        var b = new ContextBuilder(1_000, 60, 0);
        // One message of 3,984 characters estimates to 996 + 4 = 1,000 tokens, which is the headroom.
        var built = b.build(input(List.of(), List.of(message(3_984))));
        assertEquals(1_000, built.headroom());
        assertEquals(1_000, built.estimate());
        assertFalse(built.overBudget(), "the check is a strict greater than");
    }

    @Test
    void aRequestOneTokenOverTheHeadroomIsOverBudget() {
        var b = new ContextBuilder(1_000, 60, 0);
        // 3,988 characters estimates to 997 + 4 = 1,001 tokens, one over the headroom.
        var built = b.build(input(List.of(), List.of(message(3_988))));
        assertEquals(1_001, built.estimate());
        assertTrue(built.overBudget());
    }

    @Test
    void aRequestOverTheSoftBudgetButUnderTheHeadroomIsNotOverBudget() {
        var b = new ContextBuilder(1_000, 60, 0);
        // 600 tokens is the soft budget and 1,000 is the headroom: 800 tokens is between them.
        var built = b.build(input(List.of(), List.of(message(3_184))));
        assertEquals(800, built.estimate());
        assertTrue(built.estimate() > built.budget());
        assertFalse(built.overBudget());
    }

    @Test
    void theOldestTurnsLeaveUntilNinetyPercentOfTheSoftBudget() {
        var b = new ContextBuilder(1_000, 60, 0);
        var big = "x".repeat(2_000);
        var built = b.build(input(List.of(turn(1, big), turn(2, big), turn(3, "small")), List.of(message(4))));
        assertEquals(2, built.droppedTurns());
        assertEquals(List.of(1, 2), built.droppedTurnNumbers());
        assertFalse(built.overBudget());
        assertTrue(built.estimate() <= (long) built.budget() * 90 / 100, built.toString());
    }

    @Test
    void aTrimmedRequestOverTheHeadroomIsStillOverBudget() {
        var b = new ContextBuilder(1_000, 60, 0);
        var built = b.build(input(List.of(turn(1, "x".repeat(2_000))), List.of(message(4_000))));
        assertEquals(1, built.droppedTurns(), "every recent turn left the request");
        assertTrue(built.estimate() > built.headroom(), built.toString());
        assertTrue(built.overBudget(), "the current turn alone is over the hard ceiling");
    }
}
