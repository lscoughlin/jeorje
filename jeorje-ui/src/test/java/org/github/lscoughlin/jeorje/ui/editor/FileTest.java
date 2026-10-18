package org.github.lscoughlin.jeorje.ui.editor;

import static org.github.lscoughlin.jeorje.ui.editor.EditorFixture.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTest {

    @TempDir
    Path dir;

    @Test
    void openShowsTheFileAndTheNameInTheFrame() throws IOException {
        var f = new EditorFixture(dir);
        f.write("a.txt", "first\nsecond\n");
        f.editor.open(Path.of("a.txt"));
        assertEquals("first\nsecond\n", f.editor.text());
        assertTrue(f.screen().get(1).contains("a.txt"));
        assertTrue(f.screenHas("second"));
        assertFalse(f.editor.isModified());
    }

    @Test
    void saveWritesTheFileAndReportsTheStateToTheAgent() throws IOException {
        var f = new EditorFixture(dir);
        f.write("a.txt", "one\ntwo\n");
        f.editor.open(dir.resolve("a.txt"));
        f.type(CTRL_END + "three");
        assertTrue(f.editor.isModified());
        assertTrue(f.screen().get(1).contains("*"));
        f.type(CTRL_S);
        assertEquals("one\ntwo\nthree", f.read("a.txt"));
        assertFalse(f.editor.isModified());
        assertEquals(1, f.saved.size());
        assertEquals(dir.resolve("a.txt").toString(), f.saved.get(0));
        var expected = FileIo.hash("one\ntwo\nthree".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, f.savedStates.get(0).hash());
        assertEquals(Files.getLastModifiedTime(dir.resolve("a.txt")).toMillis(), f.savedStates.get(0).modifiedMillis());
        assertTrue(f.status().contains("Saved a.txt"));
    }

    @Test
    void saveLeavesNoTemporaryFile() throws IOException {
        var f = new EditorFixture(dir);
        f.write("a.txt", "x");
        f.editor.open(dir.resolve("a.txt"));
        f.type("y" + CTRL_S);
        try (var list = Files.list(dir)) {
            assertEquals("a.txt", list.map(p -> p.getFileName().toString()).collect(Collectors.joining(",")));
        }
    }

    @Test
    void saveKeepsPermissions() throws IOException {
        var f = new EditorFixture(dir);
        var path = f.write("run.sh", "echo\n");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-x---"));
        f.editor.open(path);
        f.type("# " + CTRL_S);
        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
    }

    @Test
    void crlfFilesKeepTheirLineEnds() throws IOException {
        var f = new EditorFixture(dir);
        f.write("w.txt", "one\r\ntwo\r\n");
        f.editor.open(dir.resolve("w.txt"));
        assertEquals("one\ntwo\n", f.editor.text());
        f.type(CTRL_HOME + "zero" + ENTER + CTRL_S);
        assertEquals("zero\r\none\r\ntwo\r\n", f.read("w.txt"));
    }

    @Test
    void lfFilesStayLf() throws IOException {
        var f = new EditorFixture(dir);
        f.write("u.txt", "one\ntwo\n");
        f.editor.open(dir.resolve("u.txt"));
        f.type(CTRL_HOME + "x" + CTRL_S);
        assertEquals("xone\ntwo\n", f.read("u.txt"));
    }

    @Test
    void missingFinalNewlineIsKept() throws IOException {
        var f = new EditorFixture(dir);
        f.write("n.txt", "one\ntwo");
        f.editor.open(dir.resolve("n.txt"));
        f.type(CTRL_END + "!" + CTRL_S);
        assertEquals("one\ntwo!", f.read("n.txt"));
    }

    @Test
    void finalNewlineIsKept() throws IOException {
        var f = new EditorFixture(dir);
        f.write("n.txt", "one\ntwo\n");
        f.editor.open(dir.resolve("n.txt"));
        f.type(CTRL_HOME + "!" + CTRL_S);
        assertEquals("!one\ntwo\n", f.read("n.txt"));
    }

    @Test
    void crlfWithoutFinalNewline() throws IOException {
        var f = new EditorFixture(dir);
        f.write("n.txt", "one\r\ntwo");
        f.editor.open(dir.resolve("n.txt"));
        f.type(CTRL_END + "!" + CTRL_S);
        assertEquals("one\r\ntwo!", f.read("n.txt"));
    }

    @Test
    void binaryFilesAreRefused() throws IOException {
        var f = new EditorFixture(dir);
        f.writeBytes("b.bin", new byte[] {1, 2, 0, 3});
        var error = assertThrows(IOException.class, () -> f.editor.open(dir.resolve("b.bin")));
        assertTrue(error.getMessage().contains("binary"));
    }

    @Test
    void invalidUtf8IsRefused() throws IOException {
        var f = new EditorFixture(dir);
        f.writeBytes("b.txt", new byte[] {'a', (byte) 0xC3, 0x28});
        assertThrows(IOException.class, () -> f.editor.open(dir.resolve("b.txt")));
    }

    @Test
    void unicodeRoundTrip() throws IOException {
        var f = new EditorFixture(dir);
        f.write("u.txt", "café 中文\n");
        f.editor.open(dir.resolve("u.txt"));
        f.type(CTRL_END + "!" + CTRL_S);
        assertArrayEquals("café 中文\n!".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(dir.resolve("u.txt")));
    }

    @Test
    void newFileIsCreatedBySaveAs() throws IOException {
        var f = new EditorFixture(dir);
        f.type("fresh text");
        f.type(CTRL_S);
        assertEquals("Save As", f.editor.dialogTitle());
        f.type("sub/new.txt" + ENTER);
        assertFalse(f.editor.dialogOpen());
        assertEquals("fresh text", f.read("sub/new.txt"));
        assertEquals(dir.resolve("sub/new.txt"), f.editor.path());
        assertEquals(1, f.saved.size());
    }

    @Test
    void openOfAMissingPathStartsAnEmptyFileThatSaveCreates() throws IOException {
        var f = new EditorFixture(dir);
        f.editor.open(Path.of("later.txt"));
        assertEquals("", f.editor.text());
        f.type("hi" + CTRL_S);
        assertEquals("hi", f.read("later.txt"));
    }

    @Test
    void saveAsFromTheMenuReplacesAnExistingFileOnlyAfterConfirm() throws IOException {
        var f = new EditorFixture(dir);
        f.write("old.txt", "old");
        f.type("new");
        f.type(F10 + DOWN + ENTER);
        assertEquals("Save As", f.editor.dialogTitle());
        f.type("old.txt" + ENTER);
        assertEquals("Replace file", f.editor.dialogTitle());
        assertEquals("old", f.read("old.txt"));
        f.type(ESC);
        assertEquals("old", f.read("old.txt"));
        f.type(F10 + DOWN + ENTER + "old.txt" + ENTER);
        f.type(ENTER); // the default button is Cancel, so move to Replace
        assertEquals("old", f.read("old.txt"));
        f.type(F10 + DOWN + ENTER + "old.txt" + ENTER + LEFT + ENTER);
        assertEquals("new", f.read("old.txt"));
    }

    @Test
    void saveAsWithAnEmptyNameDoesNothing() {
        var f = new EditorFixture(dir);
        f.type("x" + CTRL_S + ENTER);
        assertFalse(f.editor.dialogOpen());
        assertTrue(f.status().contains("file name is needed"));
    }

    @Test
    void closeWithoutChangesClosesAtOnce() throws IOException {
        var f = new EditorFixture(dir);
        f.write("a.txt", "x");
        f.editor.open(dir.resolve("a.txt"));
        f.type(ALT_X);
        assertTrue(f.editor.isClosed());
    }

    @Test
    void closeWithChangesAsksAndCancelKeepsTheEditor() throws IOException {
        var f = new EditorFixture(dir);
        f.write("a.txt", "x");
        f.editor.open(dir.resolve("a.txt"));
        f.type("y");
        f.type(ALT_X);
        assertEquals("Unsaved changes", f.editor.dialogTitle());
        assertTrue(f.screenHas("< Save >"));
        assertTrue(f.screenHas("< Discard >"));
        assertTrue(f.screenHas("< Cancel >"));
        f.type(ESC);
        assertFalse(f.editor.isClosed());
        assertEquals("yx", f.editor.text());
        f.type(ALT_X + TAB + TAB + ENTER);
        assertFalse(f.editor.isClosed());
    }

    @Test
    void closeWithDiscardLeavesTheFile() throws IOException {
        var f = new EditorFixture(dir);
        f.write("a.txt", "x");
        f.editor.open(dir.resolve("a.txt"));
        f.type("y" + ALT_X + TAB + ENTER);
        assertTrue(f.editor.isClosed());
        assertEquals("x", f.read("a.txt"));
    }

    @Test
    void closeWithSaveWritesAndCloses() throws IOException {
        var f = new EditorFixture(dir);
        f.write("a.txt", "x");
        f.editor.open(dir.resolve("a.txt"));
        f.type("y" + ALT_X + ENTER);
        assertTrue(f.editor.isClosed());
        assertEquals("yx", f.read("a.txt"));
        assertEquals(1, f.saved.size());
    }

    @Test
    void closeOfANewFileWithSaveAsksForAName() throws IOException {
        var f = new EditorFixture(dir);
        f.type("data" + ALT_X + ENTER);
        assertEquals("Save As", f.editor.dialogTitle());
        f.type("made.txt" + ENTER);
        assertTrue(f.editor.isClosed());
        assertEquals("data", f.read("made.txt"));
    }

    @Test
    void revertAsksWhenThereAreChanges() throws IOException {
        var f = new EditorFixture(dir);
        f.write("a.txt", "orig");
        f.editor.open(dir.resolve("a.txt"));
        f.type("more");
        f.type(F10 + DOWN + DOWN + ENTER);
        assertEquals("Revert", f.editor.dialogTitle());
        f.type(ESC);
        assertEquals("moreorig", f.editor.text());
        f.type(F10 + DOWN + DOWN + ENTER + LEFT + ENTER);
        assertEquals("orig", f.editor.text());
        assertFalse(f.editor.isModified());
    }

    // ---- disk changes ----

    @Test
    void diskChangeShowsNoticeWithReloadAndShowChanges() throws IOException {
        var f = new EditorFixture(dir);
        var path = f.write("a.txt", "one\ntwo\n");
        f.editor.open(path);
        assertFalse(f.editor.diskChanged());
        Files.writeString(path, "one\n2\nthree\n");
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(path).toMillis() + 5000));
        f.editor.checkDisk();
        assertTrue(f.editor.diskChanged());
        assertTrue(f.status().contains("on disk"));
        assertTrue(f.status().contains("Reload"));
        assertTrue(f.status().contains("Show Changes"));
        f.type(F10);
        assertTrue(f.screenHas("Reload"));
    }

    @Test
    void touchWithoutContentChangeIsNotAChange() throws IOException {
        var f = new EditorFixture(dir);
        var path = f.write("a.txt", "same");
        f.editor.open(path);
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(path).toMillis() + 9000));
        f.editor.checkDisk();
        assertFalse(f.editor.diskChanged());
    }

    @Test
    void reloadWithoutLocalChangesReadsTheNewFile() throws IOException {
        var f = new EditorFixture(dir);
        var path = f.write("a.txt", "one");
        f.editor.open(path);
        Files.writeString(path, "agent wrote this\n");
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(path).toMillis() + 5000));
        f.editor.checkDisk();
        f.type(F10 + DOWN + DOWN + DOWN + ENTER); // File > Reload
        assertEquals("agent wrote this\n", f.editor.text());
        assertFalse(f.editor.diskChanged());
        assertFalse(f.status().contains("on disk"));
    }

    @Test
    void reloadWithLocalChangesAsksAndCancelKeepsThem() throws IOException {
        var f = new EditorFixture(dir);
        var path = f.write("a.txt", "one");
        f.editor.open(path);
        f.type(CTRL_END + " mine");
        Files.writeString(path, "theirs");
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(path).toMillis() + 5000));
        f.editor.checkDisk();
        f.type(F10 + DOWN + DOWN + DOWN + ENTER);
        assertEquals("Reload", f.editor.dialogTitle());
        assertTrue(f.screenHas("< Show Changes >"));
        f.type(ESC);
        assertEquals("one mine", f.editor.text());
        assertTrue(f.editor.diskChanged());
        f.type(F10 + DOWN + DOWN + DOWN + ENTER + LEFT + ENTER); // Show Changes
        assertEquals("one mine", f.editor.text());
        assertTrue(f.editor.diffOpen());
        f.type(ESC);
        f.type(F10 + DOWN + DOWN + DOWN + ENTER + ENTER); // the focus starts on Cancel
        assertEquals("one mine", f.editor.text());
        assertFalse(f.editor.dialogOpen());
        f.type(F10 + DOWN + DOWN + DOWN + ENTER + LEFT + LEFT + ENTER); // Reload
        assertEquals("theirs", f.editor.text());
        assertFalse(f.editor.isModified());
    }

    @Test
    void saveOverAChangedFileAsksFirst() throws IOException {
        var f = new EditorFixture(dir);
        var path = f.write("a.txt", "one");
        f.editor.open(path);
        f.type("x");
        Files.writeString(path, "theirs");
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(path).toMillis() + 5000));
        f.type(CTRL_S);
        assertEquals("File changed on disk", f.editor.dialogTitle());
        assertEquals("theirs", f.read("a.txt"));
        assertTrue(f.saved.isEmpty());
        f.type(ESC);
        assertEquals("theirs", f.read("a.txt"));
        f.type(CTRL_S + LEFT + LEFT + ENTER); // Overwrite
        assertEquals("xone", f.read("a.txt"));
        assertEquals(1, f.saved.size());
        assertFalse(f.editor.diskChanged());
    }

    @Test
    void saveAsToTheSamePathAlsoAsksWhenTheFileChanged() throws IOException {
        var f = new EditorFixture(dir);
        var path = f.write("a.txt", "one");
        f.editor.open(path);
        f.type("x");
        Files.writeString(path, "theirs");
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(path).toMillis() + 5000));
        f.type(F10 + DOWN + ENTER); // File > Save As, the name is prefilled with the same path
        assertEquals("Save As", f.editor.dialogTitle());
        f.type(ENTER);
        assertEquals("File changed on disk", f.editor.dialogTitle());
        assertEquals("theirs", f.read("a.txt"));
        f.type(ESC);
        assertEquals("theirs", f.read("a.txt"));
    }

    @Test
    void deletedFileIsReportedAsChanged() throws IOException {
        var f = new EditorFixture(dir);
        var path = f.write("a.txt", "one");
        f.editor.open(path);
        Files.delete(path);
        f.editor.checkDisk();
        assertTrue(f.editor.diskChanged());
    }

    // ---- show changes ----

    @Test
    void showChangesComparesTheEditorWithTheFileOnDisk() throws IOException {
        var f = new EditorFixture(dir);
        f.write("a.txt", "alpha beta gamma\nsecond line\nthird\n");
        f.editor.open(dir.resolve("a.txt"));
        f.type(CTRL_HOME + SHIFT_END + "alpha DELTA gamma");
        f.type(ALT_V);
        f.type("c");
        assertTrue(f.editor.diffOpen());
        var screen = f.screenJoined();
        assertTrue(screen.contains("Show Changes"));
        assertTrue(screen.contains("File on disk"));
        assertTrue(screen.contains("alpha beta gamma"));
        assertTrue(screen.contains("alpha DELTA gamma"));
        assertTrue(screen.contains("second line"));
        f.type(ESC);
        assertFalse(f.editor.diffOpen());
        assertEquals("alpha DELTA gamma\nsecond line\nthird\n", f.editor.text());
    }

    @Test
    void showChangesWithIdenticalTextSaysSo() throws IOException {
        var f = new EditorFixture(dir);
        f.write("a.txt", "same\n");
        f.editor.open(dir.resolve("a.txt"));
        f.type(ALT_V + "c");
        assertTrue(f.screenHas("No differences."));
    }

    @Test
    void showChangesMarksChangedWords() {
        var view = new DiffView("one two three\n", "one TWO three\n", "a", "b", 3);
        var rows = view.rows();
        assertEquals(1, view.changedRows());
        var changed = rows.stream().filter(r -> r.oldText().contains("\u0001")).findFirst().orElseThrow();
        assertEquals(java.util.List.of("two"), DiffView.marked(changed.oldText()));
        assertEquals(java.util.List.of("TWO"), DiffView.marked(changed.newText()));
        assertFalse(view.identical());
    }

    @Test
    void showChangesAfterAnAgentChangeShowsBothVersions() throws IOException {
        var f = new EditorFixture(dir);
        var path = f.write("a.txt", "keep\nold line\n");
        f.editor.open(path);
        f.type(CTRL_END + "mine");
        Files.writeString(path, "keep\nnew line from agent\n");
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(path).toMillis() + 5000));
        f.editor.checkDisk();
        f.type(ALT_V + "c");
        assertTrue(f.screenHas("new line from agent"));
        assertTrue(f.screenHas("mine"));
        f.type(ESC);
        assertTrue(f.editor.diskChanged());
    }

    @Test
    void showChangesScrollsWithKeysAndWheel() throws IOException {
        var f = new EditorFixture(dir);
        f.write("a.txt", lines(100));
        f.editor.open(dir.resolve("a.txt"));
        f.type(CTRL_HOME);
        for (var i = 0; i < 100; i++) {
            f.type(DOWN + "x");
        }
        f.type(ALT_V + "c");
        var first = f.screenJoined();
        f.type(PGDN);
        assertFalse(first.equals(f.screenJoined()));
        var afterKey = f.screenJoined();
        f.wheel(40, 10, false);
        assertFalse(afterKey.equals(f.screenJoined()));
        f.type(CTRL_END);
        assertTrue(f.screenHas("line 100") || f.screenHas("x"));
        f.type(CTRL_HOME);
        assertEquals(first, f.screenJoined());
    }
}
