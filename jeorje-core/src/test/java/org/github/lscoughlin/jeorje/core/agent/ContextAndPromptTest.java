package org.github.lscoughlin.jeorje.core.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.Turn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextAndPromptTest {
    @TempDir Path dir;

    private static Turn turn(int n, String text) {
        return new Turn(new AgentId("a"), n, List.of(Message.user(text), Message.assistant("ok")));
    }

    private static ContextBuilder.Input input(List<Turn> turns, List<Message> current) {
        return new ContextBuilder.Input("sys", List.of(), List.of(), turns, current, List.of());
    }

    @Test
    void budgetIsSixtyPercentOfTheWindowAndLeavesRoomForOutput() {
        assertEquals(6_000, new ContextBuilder(10_000, 60, 1_000).budget());
        assertEquals(1_000, new ContextBuilder(10_000, 60, 9_000).budget());
    }

    @Test
    void underBudgetKeepsAllTurns() {
        var b = new ContextBuilder(10_000, 60, 1_000);
        var built = b.build(input(List.of(turn(1, "a"), turn(2, "b")), List.of(Message.user("c"))));
        assertEquals(0, built.droppedTurns());
        assertFalse(built.overBudget());
        assertEquals(5, built.request().messages().size());
        assertEquals(1_000, built.request().maxOutputTokens());
    }

    @Test
    void overBudgetDropsTheOldestTurns() {
        var b = new ContextBuilder(1_000, 60, 100);
        var big = "x".repeat(3_000);
        var built = b.build(input(List.of(turn(1, big), turn(2, big), turn(3, "small")), List.of(Message.user("now"))));
        assertEquals(2, built.droppedTurns());
        assertFalse(built.overBudget());
        assertEquals(3, built.request().messages().size());
    }

    @Test
    void currentTurnAloneCanStillBeOverBudget() {
        var b = new ContextBuilder(1_000, 60, 100);
        var built = b.build(input(List.of(turn(1, "a")), List.of(Message.user("y".repeat(5_000)))));
        assertTrue(built.overBudget());
        assertEquals(1, built.droppedTurns());
    }

    @Test
    void usageScalesTheEstimate() {
        var b = new ContextBuilder(100_000, 60, 1_000);
        var first = b.build(input(List.of(turn(1, "hello world")), List.of(Message.user("x"))));
        b.observe(first.rawEstimate(), first.rawEstimate() * 2);
        var second = b.build(input(List.of(turn(1, "hello world")), List.of(Message.user("x"))));
        assertEquals(first.rawEstimate(), second.rawEstimate());
        assertEquals(first.rawEstimate() * 2, second.estimate());
        b.observe(100, 1_000_000);
        assertEquals(4.0, b.scale());
    }

    @Test
    void pinnedItemsComeFirst() {
        var b = new ContextBuilder(100_000, 60, 1_000);
        var built = b.build(new ContextBuilder.Input("sys", List.of("PIN ME"), List.of(), List.of(), List.of(Message.user("q")), List.of()));
        var first = built.request().messages().getFirst();
        assertEquals(2, first.content().size());
        assertTrue(((org.github.lscoughlin.jeorje.core.model.Text) first.content().getFirst()).text().contains("PIN ME"));
    }

    private String prompt(JeorjeConfig.Prompt identity, String instructions, String plan) {
        var tools = List.of(new ToolDefinition("read", "Reads a file.", null));
        return SystemPromptBuilder.build(new SystemPromptBuilder.Parts(identity, tools, dir, "TestOS",
            LocalDate.parse("2026-02-03"), instructions, plan));
    }

    private void assertAlwaysThere(String p) {
        assertTrue(p.contains("- read: Reads a file."));
        assertTrue(p.contains("Working directory: " + dir));
        assertTrue(p.contains("Platform: TestOS"));
        assertTrue(p.contains("Date: 2026-02-03"));
        assertTrue(p.contains("[recall:"));
    }

    @Test
    void defaultPrompt() {
        var p = prompt(null, "", null);
        assertTrue(p.contains(SystemPromptBuilder.DEFAULT_IDENTITY));
        assertTrue(p.contains("Prefer native tools over using bash."));
        assertAlwaysThere(p);
        assertFalse(p.contains("## Instructions"));
        assertFalse(p.contains("## Plan mode"));
    }

    @Test
    void appendModeKeepsTheDefault() {
        var p = prompt(new JeorjeConfig.Prompt("append", "Prefer small changes.", ""), "Use tabs.", "Plan first.");
        assertTrue(p.contains(SystemPromptBuilder.DEFAULT_IDENTITY));
        assertTrue(p.contains("Prefer small changes."));
        assertTrue(p.contains("## Instructions\nUse tabs."));
        assertTrue(p.contains("## Plan mode\nPlan first."));
        assertAlwaysThere(p);
    }

    @Test
    void replaceModeKeepsTheOtherSections() {
        var p = prompt(new JeorjeConfig.Prompt("replace", "You are a pirate.", ""), "Use tabs.", null);
        assertTrue(p.startsWith("You are a pirate."));
        assertFalse(p.contains(SystemPromptBuilder.DEFAULT_IDENTITY));
        assertTrue(p.contains("Use tabs."));
        assertAlwaysThere(p);
    }

    @Test
    void promptCanComeFromAFile() throws Exception {
        var file = dir.resolve("system.md");
        Files.writeString(file, "From file.");
        var p = prompt(new JeorjeConfig.Prompt("replace", "", file.toString()), "", null);
        assertTrue(p.startsWith("From file."));
    }

    @Test
    void missingFileFallsBackToTheDefault() {
        var p = prompt(new JeorjeConfig.Prompt("replace", "", dir.resolve("none.md").toString()), "", null);
        assertTrue(p.contains(SystemPromptBuilder.DEFAULT_IDENTITY));
    }
}
