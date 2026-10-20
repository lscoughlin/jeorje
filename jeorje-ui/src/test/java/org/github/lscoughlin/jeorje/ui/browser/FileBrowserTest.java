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

    private String selectedName() {
        return fb.selectedRow() == null ? null : fb.selectedRow().path().getFileName().toString();
    }

    private void press(String... keys) {
        for (var key : keys) fb.handleKey(key);
    }

    @Test
    void showsEveryPathExceptTheDataOfGit() {
        assertEquals(List.of("build", "src", ".gitignore", "debug.log", "README.md"), names());
    }

    @Test
    void aGitignoreFileHasNoEffect() throws IOException {
        Files.writeString(tmp.resolve(".gitignore"), "src/\nREADME.md\n*\n");
        assertEquals(List.of("build", "src", ".gitignore", "debug.log", "README.md"), names());
        Files.writeString(tmp.resolve(".gitignore"), "[unclosed\n!\n\\\n");
        assertEquals(List.of("build", "src", ".gitignore", "debug.log", "README.md"), names());
        Files.delete(tmp.resolve(".gitignore"));
        assertEquals(List.of("build", "src", "debug.log", "README.md"), names());
    }

    @Test
    void dotShowsAndHidesTheDataOfGit() {
        fb.handleKey(".");
        assertTrue(fb.showsHidden());
        assertEquals(List.of(".git", "build", "src", ".gitignore", "debug.log", "README.md"), names());
        fb.handleKey(".");
        assertFalse(fb.showsHidden());
        assertFalse(names().contains(".git"));
    }

    @Test
    void theDataOfJeorjeIsHiddenAndOtherFilesOfItShow() throws IOException {
        file(".jeorje/sessions/x");
        file(".jeorje/worktrees/y");
        file(".jeorje/config.yaml");
        assertEquals(List.of(".jeorje", "build"), names().subList(0, 2));
        assertEquals(".jeorje", selectedName());
        press("ENTER");
        assertEquals(List.of(".jeorje", "config.yaml"), names().subList(0, 2));
        press(".");
        assertEquals(List.of(".git", ".jeorje", "sessions", "worktrees", "config.yaml"), names().subList(0, 5));
    }

    @Test
    void aNestedGitDirectoryIsAnOrdinaryDirectory() throws IOException {
        file("sub/.git/config");
        fb.setFilter("config");
        assertEquals(List.of("sub", ".git", "config"), names());
    }

    @Test
    void aFilterEndsOnALinkToTheParentAndDoesNotRepeatRows() throws IOException {
        Files.createSymbolicLink(tmp.resolve("loop"), tmp);
        fb.setFilter("main");
        assertEquals(List.of("src", "Main.java"), names());
    }

    @Test
    void aLinkToADirectoryShowsAndOpens() throws IOException {
        Files.createSymbolicLink(tmp.resolve("link"), tmp.resolve("src"));
        assertTrue(names().contains("link"));
        var index = names().indexOf("link");
        for (var i = 0; i < index; i++) press("DOWN");
        press("ENTER");
        assertEquals(List.of("util", "Main.java"), names().subList(index + 1, index + 3));
    }

    @Test
    void rightOnAClosedDirectoryOpensItAndSelectsTheFirstChild() {
        press("DOWN", "RIGHT");
        assertEquals(List.of("build", "src", "util", "Main.java", ".gitignore", "debug.log", "README.md"), names());
        assertEquals("util", selectedName());
    }

    @Test
    void rightTwiceOpensNestedDirectoriesAndKeepsTheParentOpen() {
        press("DOWN", "RIGHT", "RIGHT");
        // The second Right acts on the new selection: it opens src/util and moves to Helper.java.
        assertEquals(List.of("build", "src", "util", "Helper.java", "Main.java", ".gitignore", "debug.log",
                "README.md"), names());
        assertEquals("Helper.java", selectedName());
        assertTrue(fb.rows().get(1).expanded());
        assertTrue(fb.rows().get(2).expanded());
    }

    @Test
    void rightOnAFileChangesNoRowAndNoSelection() {
        press("DOWN", "DOWN", "DOWN", "DOWN");
        assertEquals("README.md", selectedName());
        var before = names();
        fb.handleKey("RIGHT");
        assertEquals(before, names());
        assertEquals("README.md", selectedName());
    }

    @Test
    void leftOnAnOpenDirectoryClosesItAndKeepsTheSelection() {
        press("DOWN", "RIGHT", "UP");
        assertEquals("src", selectedName());
        fb.handleKey("LEFT");
        assertEquals("src", selectedName());
        assertEquals(List.of("build", "src", ".gitignore", "debug.log", "README.md"), names());
        assertFalse(fb.rows().get(1).expanded());
    }

    @Test
    void leftOnTheClosedTopLevelDirectoryKeepsTheSelectionAndTheRows() {
        press("DOWN");
        var before = names();
        fb.handleKey("LEFT");
        assertEquals(before, names());
        assertEquals("src", selectedName());
    }

    @Test
    void leftOnAFileSelectsTheParentRow() {
        press("DOWN", "RIGHT", "DOWN");
        assertEquals("Main.java", selectedName());
        fb.handleKey("LEFT");
        assertEquals("src", selectedName());
    }

    @Test
    void enterOpensFileAndTogglesDirectory() {
        press("DOWN", "ENTER");
        assertTrue(fb.rows().get(1).expanded());
        assertTrue(opened.isEmpty());
        press("DOWN", "DOWN", "ENTER");
        assertEquals(List.of(tmp.resolve("src/Main.java")), opened);
    }

    @Test
    void aAddsSelectedPath() {
        press("DOWN", "DOWN", "DOWN", "DOWN", "a");
        assertEquals(List.of(tmp.resolve("README.md")), added);
    }

    @Test
    void aAddsAPathThatGitignoreLists() {
        press("a");
        assertEquals(List.of(tmp.resolve("build")), added);
        press("DOWN", "DOWN", "DOWN", "a");
        assertEquals(tmp.resolve("debug.log"), added.getLast());
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
        assertEquals(5, fb.rows().size());
    }

    @Test
    void filterFindsAPathThatGitignoreLists() {
        fb.setFilter("out");
        assertEquals(List.of("build", "out.class"), names());
        fb.setFilter("debug");
        assertEquals(List.of("debug.log"), names());
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
        press("DOWN", "ENTER", "DOWN", "ENTER");
        assertEquals(8, fb.rows().size());
        fb.onMouse(new RegionEvent(Type.Wheel, Button.WheelDown, Set.of(), 0, 0, 0));
        assertEquals(1, fb.scrollOffset());
        assertEquals(2, fb.selectedIndex());
        fb.onMouse(new RegionEvent(Type.Pressed, Button.Button1, Set.of(), 2, 2, 1));
        assertEquals(3, fb.selectedIndex());
    }

    @Test
    void aGlobWithoutASlashMatchesNames() {
        fb.setFilter("*.java");
        assertEquals(List.of("src", "util", "Helper.java", "Main.java"), names());
        assertTrue(fb.rows().get(0).expanded());
        assertTrue(fb.rows().get(1).expanded());
        fb.setFilter("*.log");
        assertEquals(List.of("debug.log"), names());
        fb.setFilter("m?in.*");
        assertEquals(List.of("src", "Main.java"), names());
        fb.setFilter("{README,debug}.*");
        assertEquals(List.of("debug.log", "README.md"), names());
    }

    @Test
    void aGlobWithASlashMatchesThePathFromTheRoot() {
        fb.setFilter("src/util/*");
        assertEquals(List.of("src", "util", "Helper.java"), names());
        fb.setFilter("**/Helper.java");
        assertEquals(List.of("src", "util", "Helper.java"), names());
        fb.setFilter("src/*.java");
        assertEquals(List.of("src", "Main.java"), names());
    }

    @Test
    void aLeadingDoubleStarAlsoMatchesAFileAtTheRoot() {
        fb.setFilter("**/README.md");
        assertEquals(List.of("README.md"), names());
    }

    @Test
    void aGlobIgnoresCase() {
        fb.setFilter("*.JAVA");
        assertEquals(List.of("src", "util", "Helper.java", "Main.java"), names());
    }

    @Test
    void textWithoutGlobCharactersStillMatchesPartOfAName() {
        fb.setFilter("readme");
        assertEquals(List.of("README.md"), names());
    }

    @Test
    void anIncompleteGlobMatchesAsTextAndThrowsNothing() {
        fb.setFilter("[");
        assertTrue(names().isEmpty());
        fb.setFilter("*.{java");
        assertTrue(names().isEmpty());
        fb.setFilter("[a");
        assertTrue(names().isEmpty());
    }

    @Test
    void anIncompleteGlobMatchesAFileNameThatHoldsTheText() throws IOException {
        file("odd[name.txt");
        fb.setFilter("[name");
        assertEquals(List.of("odd[name.txt"), names());
    }

    @Test
    void typingAGlobKeyByKeyBuildsTheFilter() {
        press("/", "*", ".", "j");
        assertEquals("*.j", fb.filter());
        assertTrue(names().isEmpty());
        press("a", "v", "a");
        assertEquals("*.java", fb.filter());
        assertTrue(fb.isFiltering());
        assertEquals(List.of("src", "util", "Helper.java", "Main.java"), names());
        press("ESC");
        assertEquals("", fb.filter());
        assertEquals(List.of("build", "src", ".gitignore", "debug.log", "README.md"), names());
    }

    @Test
    void aSlashInsideTheFilterTextDoesNotLeaveFilterMode() {
        press("/", "s", "r", "c", "/", "*");
        assertTrue(fb.isFiltering());
        assertEquals("src/*", fb.filter());
        assertEquals(List.of("src", "util", "Main.java"), names());
    }

    @Test
    void aGlobFilterEndsOnALinkToTheParent() throws IOException {
        Files.createSymbolicLink(tmp.resolve("loop"), tmp);
        fb.setFilter("*.java");
        assertEquals(List.of("src", "util", "Helper.java", "Main.java"), names());
    }

    @Test
    void escClosesBrowser() {
        fb.handleKey("ESC");
        assertFalse(fb.isOpen());
    }
}
