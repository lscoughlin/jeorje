package org.github.lscoughlin.jeorje.ui.switcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.github.lscoughlin.jeorje.core.directory.AgentEntry;
import org.github.lscoughlin.jeorje.core.directory.InMemoryAgentDirectory;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;
import org.github.lscoughlin.jeorje.ui.screen.FakeTerminal;
import org.github.lscoughlin.jeorje.ui.screen.Screen;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentSwitcherPaneTest {
    private static final AgentId ROOT = new AgentId("root");
    private static final AgentId A = new AgentId("a");
    private static final AgentId B = new AgentId("b");
    private AgentSwitcher switcher;

    @BeforeEach
    void setUp() {
        var dir = new InMemoryAgentDirectory()
                .add(new AgentEntry(ROOT, "root", null, "RUNNING", null, "main", ""))
                .add(new AgentEntry(A, "alpha", ROOT, "RUNNING", null, "feat-a", ""))
                .add(new AgentEntry(B, "beta", A, "IDLE", null, null, ""));
        switcher = new AgentSwitcher(dir, ROOT, new Rect(0, 0, 60, 5), 1);
    }

    @Test
    void keysSelectAnAgent() throws Exception {
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.draw(); // the first draw probes the terminal modes and reads the reply
            ft.type("\u001b[B\u001b[B\r");
            Optional<AgentId> result = screen.runPane(new AgentSwitcherPane(switcher));
            assertEquals(Optional.of(B), result);
            assertEquals(B, switcher.foreground());
            assertFalse(switcher.isOpen());
            assertFalse(screen.paneActive());
        }
    }

    @Test
    void theRowsShowOnTheScreenWhileThePaneRuns() throws Exception {
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.draw(); // the first draw probes the terminal modes and reads the reply
            ft.type("\u001b[B\r");
            screen.runPane(new AgentSwitcherPane(switcher));
            assertTrue(ft.output().contains("Agents - "));
            assertTrue(ft.output().contains("alpha"));
        }
    }

    @Test
    void mouseClickAndDoubleClickSelect() throws Exception {
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.draw(); // the first draw probes the terminal modes and reads the reply
            // Row 0 of the screen is the title. The list starts at row 1. SGR coordinates start at 1.
            // A wheel step first, then a double click on the list row of "alpha" (tree row 2, screen row 3, SGR row 4).
            var press = "\u001b[<0;5;4M";
            var release = "\u001b[<0;5;4m";
            ft.type("\u001b[<65;5;4M" + press + release + press + release);
            var result = screen.runPane(new AgentSwitcherPane(switcher));
            assertEquals(Optional.of(A), result);
        }
    }

    @Test
    void escCancelsAndKeepsTheForeground() throws Exception {
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.draw(); // the first draw probes the terminal modes and reads the reply
            ft.type("\u001b[B\u001b");
            var result = screen.runPane(new AgentSwitcherPane(switcher));
            assertTrue(result.isEmpty());
            assertEquals(ROOT, switcher.foreground());
            assertFalse(screen.paneActive());
        }
    }

    @Test
    void theAgentsKeyClosesThePane() throws Exception {
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.draw(); // the first draw probes the terminal modes and reads the reply
            ft.type("\u0018a");
            assertTrue(screen.runPane(new AgentSwitcherPane(switcher)).isEmpty());
            assertFalse(switcher.isOpen());
            assertEquals(ROOT, switcher.foreground());
            assertFalse(screen.paneActive());
        }
    }

    @Test
    void aConfiguredAgentsKeyClosesThePaneAndTheOldChordDoesNot() throws Exception {
        var keys = org.github.lscoughlin.jeorje.ui.keys.KeyBindings.resolve(java.util.List.of(
                new org.github.lscoughlin.jeorje.ui.keys.KeyLayer("test", java.util.Map.of("agents", "Ctrl-X g"),
                        java.util.Map.of())));
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults().keyBindings(keys));
            screen.draw(); // the first draw probes the terminal modes and reads the reply
            // The old chord does nothing. Down and Enter would pick beta, so the new chord must close the pane first.
            ft.type("\u0018a\u0018g");
            assertTrue(screen.runPane(new AgentSwitcherPane(switcher)).isEmpty());
            assertEquals(ROOT, switcher.foreground());
        }
    }
}
