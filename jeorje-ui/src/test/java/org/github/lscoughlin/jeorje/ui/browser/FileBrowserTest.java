package org.github.lscoughlin.jeorje.ui.browser;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;
import org.github.lscoughlin.jeorje.ui.mouse.RegionEvent;
import org.jline.terminal.MouseEvent.Button;
import org.jline.terminal.MouseEvent.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBrowserTest {
    @TempDir Path tmp;
    private FileBrowser fb;
    private final List<Path> opened = new ArrayList<>();
    private final List<Path> added = new ArrayList<>();

    private void file(String rel) throws IOException {
        var p = tmp.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
    }

    @BeforeEach
    void setUp() throws IOException {
        file("src/Main.java");
        file("src/util/Helper.java");
        file("README.md");
        file(".git/config");
        file("build/out.class");
        file("debug.log");
        Files.writeString(tmp.resolve(".gitignore"), "build/\n*.log\n");
        fb = new FileBrowser(tmp, new Rect(0, 0, 60, 3), 1);
        fb.onOpen(opened::add);
        fb.onAdd(added::add);
        fb.open();
    }

    private List<String> names() {
        return fb.rows().stream().map(r -> r.path().getFileName().toString()).toList();
    }

    @Test
    void hidesGitAndIgnoredPathsByDefault() {
        assertEquals(List.of("src", ".gitignore", "README.md"), names());
    }

    @Test
    void dotShowsIgnoredPaths() {
        fb.handleKey(".");
        assertTrue(fb.showsIgnored());
        assertTrue(names().containsAll(List.of(".git", "build", "debug.log")));
        fb.handleKey(".");
        assertFalse(names().contains(".git"));
    }

    private String selectedName() {
        return fb.selectedRow() == null ? null : fb.selectedRow().path().getFileName().toString();
    }

    @Test
    void rightOnAClosedDirectoryOpensItAndSelectsTheFirstChild() {
        fb.handleKey("RIGHT");
        assertEquals(List.of("src", "util", "Main.java", ".gitignore", "README.md"), names());
        assertEquals("util", selectedName());
    }

    @Test
    void rightTwiceOpensNestedDirectoriesAndKeepsTheParentOpen() {
        fb.handleKey("RIGHT");
        fb.handleKey("RIGHT");
        // The second Right acts on the new selection: it opens src/util and moves to Helper.java.
        assertEquals(List.of("src", "util", "Helper.java", "Main.java", ".gitignore", "README.md"), names());
        assertEquals("Helper.java", selectedName());
        assertTrue(fb.rows().get(0).expanded());
        assertTrue(fb.rows().get(1).expanded());
    }

    @Test
    void rightOnAFileChangesNoRowAndNoSelection() {
        fb.handleKey("DOWN");
        fb.handleKey("DOWN");
        assertEquals("README.md", selectedName());
        var before = names();
        fb.handleKey("RIGHT");
        assertEquals(before, names());
        assertEquals("README.md", selectedName());
    }

    @Test
    void leftOnAnOpenDirectoryClosesItAndKeepsTheSelection() {
        fb.handleKey("RIGHT");
        fb.handleKey("UP");
        assertEquals("src", selectedName());
        fb.handleKey("LEFT");
        assertEquals("src", selectedName());
        assertEquals(List.of("src", ".gitignore", "README.md"), names());
        assertFalse(fb.rows().get(0).expanded());
    }

    @Test
    void leftOnTheClosedTopLevelDirectoryKeepsTheSelectionAndTheRows() {
        var before = names();
        fb.handleKey("LEFT");
        assertEquals(before, names());
        assertEquals("src", selectedName());
    }

    @Test
    void leftOnAFileSelectsTheParentRow() {
        fb.handleKey("RIGHT");
        fb.handleKey("DOWN");
        assertEquals("Main.java", selectedName());
        fb.handleKey("LEFT");
        assertEquals("src", selectedName());
    }

    @Test
    void enterOpensFileAndTogglesDirectory() {
        fb.handleKey("ENTER");
        assertTrue(fb.rows().get(0).expanded());
        assertTrue(opened.isEmpty());
        fb.handleKey("DOWN");
        fb.handleKey("DOWN");
        fb.handleKey("ENTER");
        assertEquals(List.of(tmp.resolve("src/Main.java")), opened);
    }

    @Test
    void aAddsSelectedPath() {
        fb.handleKey("DOWN");
        fb.handleKey("DOWN");
        fb.handleKey("a");
        assertEquals(List.of(tmp.resolve("README.md")), added);
    }

    @Test
    void filterMatchesNamesAndKeepsParents() {
        fb.handleKey("/");
        assertTrue(fb.isFiltering());
        for (var c : "help".split("")) fb.handleKey(c);
        assertEquals("help", fb.filter());
        assertEquals(List.of("src", "util", "Helper.java"), names());
        fb.handleKey("BACKSPACE");
        assertEquals("hel", fb.filter());
        fb.handleKey("ESC");
        assertFalse(fb.isFiltering());
        assertEquals("", fb.filter());
        assertEquals(3, fb.rows().size());
    }

    @Test
    void filterModeDoesNotRunBrowserKeys() {
        fb.handleKey("/");
        fb.handleKey("a");
        assertTrue(added.isEmpty());
        assertEquals("a", fb.filter());
    }

    @Test
    void wheelScrollsWithoutMovingSelectionAndClickSelects() {
        fb.handleKey("ENTER");
        fb.handleKey("DOWN");
        fb.handleKey("ENTER");
        assertEquals(6, fb.rows().size());
        fb.onMouse(new RegionEvent(Type.Wheel, Button.WheelDown, Set.of(), 0, 0, 0));
        assertEquals(1, fb.scrollOffset());
        assertEquals(1, fb.selectedIndex());
        fb.onMouse(new RegionEvent(Type.Pressed, Button.Button1, Set.of(), 2, 2, 1));
        assertEquals(3, fb.selectedIndex());
    }

    @Test
    void escClosesBrowser() {
        fb.handleKey("ESC");
        assertFalse(fb.isOpen());
    }
}
