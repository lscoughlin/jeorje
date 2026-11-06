package org.github.lscoughlin.jeorje.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The activity row between the chat and the input line. */
class ScreenActivityTest {
    private static final AgentId MAIN = new AgentId("main");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
    private static final int ROWS = 10;

    private static String row(Screen screen, int index) {
        return screen.renderRegion(ROWS, 80).get(index).toString();
    }

    @Test
    void theRowShowsAboveTheInputLineAndTheChatLosesOneRow() throws Exception {
        try (var ft = new FakeTerminal(80, ROWS)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.activity(CLOCK, false);
            var before = screen.renderRegion(ROWS, 80);
            assertEquals(ROWS - 2, before.size());
            assertEquals(ROWS - 2, screen.renderScreen(ROWS, 80).size() - 2);
            screen.apply(new AgentEvent.Working(MAIN, true, 8400, 1200));
            var during = screen.renderRegion(ROWS, 80);
            assertEquals(before.size(), during.size(), "the region keeps its height");
            assertEquals("⠋ Thinking... (0s | ↑ 8.4k tokens | ↓ 1.2k tokens)", during.getLast().toString());
            assertTrue(before.getLast().toString().isBlank());
            screen.apply(new AgentEvent.Working(MAIN, false, 8400, 1200));
            var after = screen.renderRegion(ROWS, 80);
            assertTrue(after.getLast().toString().isBlank());
        }
    }

    @Test
    void theChatViewGetsOneRowLessWhileTheRowShows() throws Exception {
        try (var ft = new FakeTerminal(80, ROWS)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.activity(CLOCK, false);
            for (var i = 0; i < 20; i++) screen.apply(new AgentEvent.TokenDelta(MAIN, "line " + i + "\n"));
            var full = screen.renderRegion(ROWS, 80);
            screen.apply(new AgentEvent.Working(MAIN, true, 1, 1));
            var shown = screen.renderRegion(ROWS, 80);
            var chatShown = shown.subList(0, shown.size() - 1).stream().map(Object::toString).toList();
            var chatFull = full.stream().map(Object::toString).toList();
            assertEquals(chatFull.size() - 1, chatShown.size());
            assertEquals(chatFull.subList(1, chatFull.size()), chatShown, "the chat scrolls up by one row");
            screen.apply(new AgentEvent.Working(MAIN, false, 1, 1));
            assertEquals(full.stream().map(Object::toString).toList(),
                    screen.renderRegion(ROWS, 80).stream().map(Object::toString).toList());
        }
    }

    @Test
    void aBackgroundAgentShowsNoRowAndTheNewForegroundShowsItsOwnState() throws Exception {
        try (var ft = new FakeTerminal(80, ROWS)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.activity(CLOCK, false);
            var other = new AgentId("sub1");
            screen.apply(new AgentEvent.Working(other, true, 5, 6));
            assertTrue(row(screen, ROWS - 3).isBlank());
            assertFalse(screen.tickActivity(), "no tick for an idle foreground");
            screen.setForeground(other);
            assertTrue(row(screen, ROWS - 3).contains("↑ 5 tokens | ↓ 6 tokens"), row(screen, ROWS - 3));
            screen.setForeground(MAIN);
            assertTrue(row(screen, ROWS - 3).isBlank());
        }
    }

    @Test
    void thePromptHidesTheRow() throws Exception {
        try (var ft = new FakeTerminal(80, ROWS + 6)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.activity(CLOCK, false);
            screen.apply(new AgentEvent.Working(MAIN, true, 5, 6));
            assertTrue(screen.renderRegion(ROWS + 6, 80).getLast().toString().contains("tokens"));
            var input = JsonMapper.builder().build().createObjectNode().put("command", "ls");
            screen.apply(new AgentEvent.ConfirmationRequested(MAIN, new ToolUse("t1", "bash", input)));
            var region = screen.renderRegion(ROWS + 6, 80);
            assertTrue(region.stream().noneMatch(l -> l.toString().contains("↑ 5 tokens")));
        }
    }

    @Test
    void theTickMovesTheFrameOfTheForegroundRow() throws Exception {
        try (var ft = new FakeTerminal(80, ROWS)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.activity(CLOCK, false);
            screen.apply(new AgentEvent.Working(MAIN, true, 5, 6));
            assertTrue(row(screen, ROWS - 3).startsWith("⠋"));
            assertTrue(screen.tickActivity());
            assertTrue(row(screen, ROWS - 3).startsWith("⠙"));
        }
    }

    @Test
    void theStatusLineDoesNotChange() throws Exception {
        try (var ft = new FakeTerminal(80, ROWS)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.activity(CLOCK, false);
            var before = screen.status().text();
            screen.apply(new AgentEvent.Working(MAIN, true, 5, 6));
            assertEquals(before, screen.status().text());
        }
    }
}
