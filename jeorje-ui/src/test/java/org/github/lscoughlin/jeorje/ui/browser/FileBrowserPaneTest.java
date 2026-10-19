package org.github.lscoughlin.jeorje.ui.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;
import org.github.lscoughlin.jeorje.ui.screen.FakeTerminal;
import org.github.lscoughlin.jeorje.ui.screen.Screen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBrowserPaneTest {

    private FileBrowser browser(Path root) throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Main.java"), "class Main {}");
        Files.writeString(root.resolve("README.md"), "x");
        return new FileBrowser(root, new Rect(0, 0, 60, 5), 1);
    }

    @Test
    void enterOnAFileReturnsThePath(@TempDir Path root) throws Exception {
        var browser = browser(root);
        var opened = new ArrayList<Path>();
        browser.onOpen(opened::add);
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.draw(); // the first draw probes the terminal modes and reads the reply
            // rows: src/, README.md. Down to README.md, then Enter.
            ft.type("\u001b[B\r");
            var result = screen.runPane(new FileBrowserPane(browser));
            assertEquals(root.toAbsolutePath().normalize().resolve("README.md"), result.orElseThrow());
            assertTrue(opened.isEmpty(), "the pane picks and does not open the editor");
            assertFalse(browser.isOpen());
            assertFalse(screen.paneActive());
            assertTrue(ft.output().contains("README.md"));
        }
    }

    @Test
    void rightOpensADirectoryAndEnterPicksAFileInIt(@TempDir Path root) throws Exception {
        var browser = browser(root);
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.draw(); // the first draw probes the terminal modes and reads the reply
            // Right opens src and moves to Main.java, the only child. Enter picks it.
            ft.type("\u001b[C\r");
            var result = screen.runPane(new FileBrowserPane(browser));
            assertEquals(root.toAbsolutePath().normalize().resolve("src/Main.java"), result.orElseThrow());
        }
    }

    @Test
    void doubleClickPicksAFile(@TempDir Path root) throws Exception {
        var browser = browser(root);
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.draw(); // the first draw probes the terminal modes and reads the reply
            // README.md is list row 1: screen row 2, SGR row 3.
            var press = "\u001b[<0;5;3M";
            var release = "\u001b[<0;5;3m";
            ft.type(press + release + press + release);
            var result = screen.runPane(new FileBrowserPane(browser));
            assertEquals("README.md", result.orElseThrow().getFileName().toString());
        }
    }

    @Test
    void escCancels(@TempDir Path root) throws Exception {
        var browser = browser(root);
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.draw(); // the first draw probes the terminal modes and reads the reply
            ft.type("\u001b[B\u001b");
            assertTrue(screen.runPane(new FileBrowserPane(browser)).isEmpty());
            assertFalse(browser.isOpen());
            assertFalse(screen.paneActive());
        }
    }

    private static org.github.lscoughlin.jeorje.ui.keys.KeyBindings keys(java.util.Map<String, Object> ui) {
        return org.github.lscoughlin.jeorje.ui.keys.KeyBindings.resolve(java.util.List.of(
                new org.github.lscoughlin.jeorje.ui.keys.KeyLayer("test", ui, java.util.Map.of())));
    }

    @Test
    void configuredBrowserKeysReplaceTheDefaults(@TempDir Path root) throws Exception {
        var browser = browser(root);
        var keys = keys(java.util.Map.of("browserEdit", "Ctrl-O", "browserExit", "q"));
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults().keyBindings(keys));
            screen.draw(); // the first draw probes the terminal modes and reads the reply
            // Enter and Esc do nothing now. Down moves to README.md and Ctrl-O picks it.
            ft.type("\r\u001b[B\u000f");
            var result = screen.runPane(new FileBrowserPane(browser, keys));
            assertEquals("README.md", result.orElseThrow().getFileName().toString());
        }
    }

    @Test
    void theConfiguredExitKeyClosesTheBrowserAndEscDoesNot(@TempDir Path root) throws Exception {
        var browser = browser(root);
        var keys = keys(java.util.Map.of("browserExit", "q"));
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults().keyBindings(keys));
            screen.draw(); // the first draw probes the terminal modes and reads the reply
            ft.type("\u001b\u001bq");
            assertTrue(screen.runPane(new FileBrowserPane(browser, keys)).isEmpty());
            assertFalse(browser.isOpen());
        }
    }

    @Test
    void theConfiguredFilterKeyStartsTheFilterAndTypedTextGoesIntoIt(@TempDir Path root) throws Exception {
        var browser = browser(root);
        var keys = keys(java.util.Map.of("browserFilter", "Ctrl-G", "browserExit", "q"));
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults().keyBindings(keys));
            screen.draw(); // the first draw probes the terminal modes and reads the reply
            // The slash does nothing. After Ctrl-G the letters q and a go into the filter, not to the actions.
            ft.type("/\u0007qa\r\u001bq");
            var pane = new FileBrowserPane(browser, keys);
            // Enter ends the filter. Esc is not the exit key now, so only the last q closes the browser.
            assertTrue(screen.runPane(pane).isEmpty());
            assertEquals("qa", browser.filter());
            assertFalse(browser.isFiltering());
        }
    }

    @Test
    void theTitleShowsTheConfiguredKeys(@TempDir Path root) throws Exception {
        var browser = browser(root);
        var pane = new FileBrowserPane(browser, keys(java.util.Map.of("browserExit", "q", "browserHidden", "none")));
        assertTrue(pane.title().contains("q cancel"), pane.title());
        assertTrue(pane.title().contains("- ignored"), pane.title());
    }

    @Test
    void aPaneWithoutBindingsReadsTheFixedKeys(@TempDir Path root) throws Exception {
        var pane = new FileBrowserPane(browser(root));
        assertEquals(null, pane.keyContext());
        assertEquals(null, pane.toggleAction());
    }

    @Test
    void theLastRowNamesTheFixedKeys(@TempDir Path root) throws Exception {
        var pane = new FileBrowserPane(browser(root));
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.draw(); // the first draw probes the terminal modes and reads the reply
            ft.type("\u001b[B\u001b[D\u001b[C\r\u001b");
            screen.runPane(pane);
            var last = pane.render().getLast();
            assertTrue(last.contains("Up/Down move"), last);
            assertTrue(last.contains("Right"), last);
            assertTrue(last.contains("Left"), last);
            assertTrue(last.contains("Enter"), last);
            assertTrue(last.contains(" a "), last);
            assertTrue(last.contains(" / "), last);
            assertTrue(last.contains("Esc"), last);
        }
    }

    @Test
    void theLastRowNamesTheConfiguredKeys(@TempDir Path root) throws Exception {
        var keys = keys(java.util.Map.of("browserEdit", "Ctrl-O", "browserExit", "q"));
        var pane = new FileBrowserPane(browser(root), keys);
        var last = pane.render().getLast();
        assertTrue(last.contains("Ctrl-O"), last);
        assertTrue(last.contains(" q"), last);
    }

    @Test
    void renderGivesTheTreeRowsAndOneKeysRow(@TempDir Path root) throws Exception {
        var browser = browser(root);
        var pane = new FileBrowserPane(browser);
        // The tree has src/ and README.md.
        assertEquals(2, browser.rows().size());
        pane.resize(new Rect(0, 1, 60, 5));
        assertEquals(5, pane.render().size());
        assertTrue(pane.render().get(0).contains("src/"), pane.render().get(0));
        assertFalse(pane.render().get(4).contains("README.md"));
        assertTrue(pane.render().get(4).contains("Up/Down move"));
        pane.resize(new Rect(0, 1, 60, 1));
        assertEquals(1, pane.render().size());
        assertTrue(pane.render().getFirst().contains("Up/Down move"));
    }

    @Test
    void enterOnADirectoryExpandsItAndKeepsThePaneOpen(@TempDir Path root) throws Exception {
        var browser = browser(root);
        var pane = new FileBrowserPane(browser);
        try (var ft = new FakeTerminal(60, 12)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults());
            screen.draw(); // the first draw probes the terminal modes and reads the reply
            // Enter expands src. Esc then closes the pane.
            ft.type("\r\u001b");
            assertTrue(screen.runPane(pane).isEmpty());
            assertTrue(browser.rows().stream().anyMatch(r -> r.path().getFileName().toString().equals("Main.java")));
            assertFalse(browser.isOpen());
        }
    }
}
