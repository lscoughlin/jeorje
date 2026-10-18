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
            ft.type("\u001b[C\u001b[B\r");
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
}
