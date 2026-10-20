package org.github.lscoughlin.jeorje.ui.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ScreenTest {
    private static final AgentId MAIN = new AgentId("main");

    private static void await(java.util.function.BooleanSupplier c) throws InterruptedException {
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!c.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(c.getAsBoolean(), "condition not reached in time");
    }

    private static int count(String text, String part) {
        var n = 0;
        for (var i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) n++;
        return n;
    }

    @Test
    void eventsRenderInOrderOnTheScreen() throws Exception {
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.post(new AgentEvent.TokenDelta(MAIN, "Hello "));
            screen.post(new AgentEvent.TokenDelta(MAIN, "world"));
            screen.post(new AgentEvent.ToolStarted(MAIN, "t1", "read", "a.txt"));
            screen.post(new AgentEvent.ToolFinished(MAIN, "t1", ToolResultData.ok("x\ny")));
            assertEquals(4, screen.drain());
            var rows = screen.renderScreen(12, 60);
            assertEquals(12, rows.size());
            assertEquals("Hello world", rows.get(0).toString().stripTrailing());
            assertTrue(rows.get(1).toString().contains("read"));
            assertTrue(rows.get(2).toString().contains("2 lines"));
            assertTrue(rows.get(11).toString().contains("main"));
            assertTrue(ft.output().contains("Hello world"));
        }
    }

    @Test
    void theContextUsageOfTheForegroundAgentSetsTheStatusLine() throws Exception {
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.apply(new AgentEvent.ContextUsage(MAIN, 30_000, 60_000));
            assertTrue(screen.status().text().contains("ctx 50%"), screen.status().text());
            screen.apply(new AgentEvent.ContextUsage(new AgentId("sub1"), 59_000, 60_000));
            assertTrue(screen.status().text().contains("ctx 50%"), "another agent does not change it");
            screen.apply(new AgentEvent.ContextUsage(MAIN, 54_000, 60_000));
            assertTrue(screen.status().text().contains("ctx 90%"), screen.status().text());
            assertEquals(90, screen.status().contextPercent());
        }
    }

    @Test
    void clearEmptiesTheScreenAndDrawsEveryRowAgain() throws Exception {
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.enterTerminal();
            try {
                screen.apply(new AgentEvent.TokenDelta(MAIN, "first topic"));
                var before = ft.output();
                screen.apply(new AgentEvent.Cleared(MAIN, 3));
                var output = ft.output().substring(before.length());
                assertEquals(1, count(output, "\u001b[2J"), "the clear code once: " + output);
                assertTrue(output.contains("New conversation."), "the screen is drawn again after the clear");
                var rows = screen.renderScreen(12, 60);
                assertTrue(rows.get(0).toString().contains("New conversation."), rows.get(0).toString());
                assertEquals(12, rows.size());
            } finally {
                screen.close();
            }
        }
    }

    @Test
    void aClearOfAnotherAgentKeepsTheScreen() throws Exception {
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.enterTerminal();
            try {
                var before = ft.output();
                screen.apply(new AgentEvent.Cleared(new AgentId("sub1"), 2));
                assertFalse(ft.output().substring(before.length()).contains("\u001b[2J"));
                assertTrue(screen.renderRegion(12, 60).get(0).toString().isBlank());
            } finally {
                screen.close();
            }
        }
    }

    @Test
    void foregroundSwitchShowsTheOtherAgent() throws Exception {
        try (var ft = new FakeTerminal(60, 8)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            var other = new AgentId("sub1");
            screen.apply(new AgentEvent.TokenDelta(MAIN, "from main"));
            screen.apply(new AgentEvent.TokenDelta(other, "from sub"));
            assertTrue(screen.renderRegion(8, 60).get(0).toString().startsWith("from main"));
            screen.setForeground(other);
            assertEquals(other, screen.foreground());
            assertTrue(screen.renderRegion(8, 60).get(0).toString().startsWith("from sub"));
            assertTrue(screen.status().text().contains("sub1"));
        }
    }

    @Test
    void promptBoxReturnsTheChosenDecision() throws Exception {
        try (var ft = new FakeTerminal(70, 16)) {
            var got = new AtomicReference<Decision>();
            var opts = new Screen.Options("auto", null, null, (a, u, d) -> got.set(d), null);
            var screen = new Screen(ft.terminal, opts);
            var input = JsonMapper.builder().build().createObjectNode().put("path", "x");
            screen.apply(new AgentEvent.ConfirmationRequested(MAIN, new ToolUse("t9", "write", input)));
            assertNotNull(screen.pendingPrompt());
            var text = String.join("\n", screen.renderRegion(16, 70).stream().map(Object::toString).toList());
            assertTrue(text.contains("Permission needed") && text.contains("write"));
            assertNull(screen.handlePromptKey("x"));
            assertEquals(Decision.ALLOW_PROJECT, screen.handlePromptKey("p"));
            assertEquals(Decision.ALLOW_PROJECT, got.get());
            assertNull(screen.pendingPrompt());
        }
    }

    @Test
    void terminalAttributesReturnAfterExit() throws Exception {
        try (var ft = new FakeTerminal(60, 12)) {
            var before = ft.terminal.getAttributes();
            assertTrue(before.getInputFlag(Attributes.InputFlag.IXON));
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.start();
            var during = ft.terminal.getAttributes();
            assertFalse(during.getInputFlag(Attributes.InputFlag.IXON));
            assertFalse(during.getInputFlag(Attributes.InputFlag.IXOFF));
            assertFalse(during.getLocalFlag(Attributes.LocalFlag.ICANON));
            assertTrue(ft.output().contains("\u001b[?1049h"), "alternate screen on");
            screen.close();
            var after = ft.terminal.getAttributes();
            assertEquals(before.getInputFlags(), after.getInputFlags());
            assertEquals(before.getLocalFlags(), after.getLocalFlags());
            assertEquals(before.getOutputFlags(), after.getOutputFlags());
            assertTrue(ft.output().contains("\u001b[?1049l"), "alternate screen off");
        }
    }

    @Test
    void resizeRedraws() throws Exception {
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.enterTerminal();
            try {
                screen.apply(new AgentEvent.TokenDelta(MAIN, "x".repeat(50)));
                assertArrayEquals(new int[] {60, 12}, screen.drawnSize());
                var before = screen.redrawCount();
                ft.terminal.setSize(new Size(20, 8));
                ft.terminal.raise(Terminal.Signal.WINCH);
                assertTrue(screen.redrawCount() > before);
                assertArrayEquals(new int[] {20, 8}, screen.drawnSize());
                var region = screen.renderRegion(8, 20);
                assertEquals(6, region.size());
                assertEquals(3, screen.chat(MAIN).wrapped(20).size());
            } finally {
                screen.close();
            }
        }
    }

    @Test
    void inputLineSubmitsWithHistoryAndMultiLine() throws Exception {
        try (var ft = new FakeTerminal(60, 12)) {
            var lines = new CopyOnWriteArrayList<String>();
            var opts = new Screen.Options("off", null, lines::add, null, null);
            var screen = new Screen(ft.terminal, opts);
            screen.start();
            try {
                ft.type("hello\r");
                await(() -> lines.size() == 1);
                ft.type("one\u001b\rtwo\r");
                await(() -> lines.size() == 2);
                assertEquals("hello", lines.get(0));
                assertEquals("one\ntwo", lines.get(1));
                assertTrue(screen.chat(MAIN).logicalLines().stream().anyMatch(l -> l.toString().equals("> hello")));
                ft.type("\u001bOA\r");
                await(() -> lines.size() == 3);
                assertEquals("one\ntwo", lines.get(2));
            } finally {
                screen.close();
            }
        }
    }

    @Test
    void keyboardAnswersAPermissionPrompt() throws Exception {
        try (var ft = new FakeTerminal(70, 16)) {
            var got = new AtomicReference<Decision>();
            var opts = new Screen.Options("off", null, null, (a, u, d) -> got.set(d), null);
            var screen = new Screen(ft.terminal, opts);
            screen.start();
            try {
                Thread.sleep(300);
                var input = JsonMapper.builder().build().createObjectNode().put("command", "ls");
                screen.post(new AgentEvent.ConfirmationRequested(MAIN, new ToolUse("t1", "bash", input)));
                await(() -> screen.pendingPrompt() != null);
                Thread.sleep(300);
                ft.type("s");
                await(() -> got.get() != null);
                assertEquals(Decision.ALLOW_SESSION, got.get());
            } finally {
                screen.close();
            }
        }
    }
}
