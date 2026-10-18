package org.github.lscoughlin.jeorje.ui.editor;

import static org.github.lscoughlin.jeorje.ui.editor.EditorFixture.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditingTest {

    @TempDir
    Path dir;

    @Test
    void typedCharactersEnterTheText() {
        var f = new EditorFixture(dir);
        f.type("hello, world 123 /?");
        assertEquals("hello, world 123 /?", f.editor.text());
        assertEquals(20, f.editor.column());
        assertTrue(f.screenHas("hello, world 123 /?"));
    }

    @Test
    void letterKeysThatAreMenuHotKeysStillTypeText() {
        var f = new EditorFixture(dir);
        f.type("fesvoh x");
        assertEquals("fesvoh x", f.editor.text());
        assertFalse(f.editor.menuOpen());
    }

    @Test
    void unicodeTextIsTyped() {
        var f = new EditorFixture(dir);
        f.type("café 中文 😀");
        assertEquals("café 中文 😀", f.editor.text());
    }

    @Test
    void enterSplitsLinesAndKeepsIndent() {
        var f = new EditorFixture(dir);
        f.type("  one" + ENTER + "two");
        assertEquals("  one\n  two", f.editor.text());
        assertEquals(2, f.editor.line());
    }

    @Test
    void arrowKeysAndHomeEndMoveTheCursor() {
        var f = new EditorFixture(dir);
        f.type("abc" + ENTER + "defgh");
        f.type(UP);
        assertEquals(1, f.editor.line());
        assertEquals(4, f.editor.column());
        f.type(HOME);
        assertEquals(1, f.editor.column());
        f.type(END);
        assertEquals(4, f.editor.column());
        f.type(RIGHT);
        assertEquals(2, f.editor.line());
        assertEquals(1, f.editor.column());
        f.type(LEFT);
        assertEquals(1, f.editor.line());
        f.type(CTRL_HOME);
        assertEquals(0, f.editor.cursor());
        f.type(CTRL_END);
        assertEquals(f.editor.text().length(), f.editor.cursor());
    }

    @Test
    void verticalMoveKeepsTheColumn() {
        var f = new EditorFixture(dir);
        f.type("abcdefgh" + ENTER + "ab" + ENTER + "abcdefgh");
        f.type(UP + UP);
        f.type(END);
        f.type(DOWN);
        assertEquals(3, f.editor.column());
        f.type(DOWN);
        assertEquals(9, f.editor.column());
    }

    @Test
    void wordMovement() {
        var f = new EditorFixture(dir);
        f.type("alpha beta  gamma");
        f.type(HOME);
        f.type(CTRL_RIGHT);
        assertEquals(6, f.editor.cursor());
        f.type(CTRL_RIGHT);
        assertEquals(12, f.editor.cursor());
        f.type(CTRL_LEFT);
        assertEquals(6, f.editor.cursor());
        f.type(CTRL_LEFT);
        assertEquals(0, f.editor.cursor());
    }

    @Test
    void shiftMovementSelectsText() {
        var f = new EditorFixture(dir);
        f.type("hello world");
        f.type(HOME);
        f.type(SHIFT_RIGHT + SHIFT_RIGHT + SHIFT_RIGHT);
        assertEquals("hel", f.editor.selectedText());
        f.type(SHIFT_LEFT);
        assertEquals("he", f.editor.selectedText());
        f.type(SHIFT_END);
        assertEquals("hello world", f.editor.selectedText());
        f.type(RIGHT);
        assertEquals("", f.editor.selectedText());
    }

    @Test
    void shiftWordMovementSelects() {
        var f = new EditorFixture(dir);
        f.type("one two three");
        f.type(HOME);
        f.type("\u001b[1;6C");
        assertEquals("one ", f.editor.selectedText());
    }

    @Test
    void typingReplacesTheSelection() {
        var f = new EditorFixture(dir);
        f.type("hello world" + HOME + SHIFT_RIGHT + SHIFT_RIGHT + SHIFT_RIGHT + SHIFT_RIGHT + SHIFT_RIGHT);
        f.type("J");
        assertEquals("J world", f.editor.text());
    }

    @Test
    void backspaceAndDeleteRemoveCharsOrSelection() {
        var f = new EditorFixture(dir);
        f.type("abcdef");
        f.type(BACKSPACE);
        assertEquals("abcde", f.editor.text());
        f.type(HOME + DEL);
        assertEquals("bcde", f.editor.text());
        f.type(SHIFT_RIGHT + SHIFT_RIGHT + DEL);
        assertEquals("de", f.editor.text());
        f.type(END + SHIFT_LEFT + BACKSPACE);
        assertEquals("d", f.editor.text());
    }

    @Test
    void backspaceJoinsLines() {
        var f = new EditorFixture(dir);
        f.type("ab" + ENTER + "cd" + HOME + BACKSPACE);
        assertEquals("abcd", f.editor.text());
    }

    @Test
    void cutCopyPasteWithConfiguredKeys() {
        var f = new EditorFixture(dir);
        f.type("hello world" + HOME + SHIFT_RIGHT + SHIFT_RIGHT + SHIFT_RIGHT + SHIFT_RIGHT + SHIFT_RIGHT);
        f.type(CTRL_INS);
        assertEquals("hello", f.clipboard.paste());
        assertEquals("hello world", f.editor.text());
        f.type(SHIFT_DEL);
        assertEquals(" world", f.editor.text());
        f.type(END + SHIFT_INS);
        assertEquals(" worldhello", f.editor.text());
        f.type(SHIFT_INS);
        assertEquals(" worldhellohello", f.editor.text());
    }

    @Test
    void ctrlCVAliasesWorkWhenThereIsASelectionOrClipboard() {
        var f = new EditorFixture(dir);
        f.type("abc");
        f.type(CTRL_C);
        assertEquals("", f.clipboard.paste());
        f.type(CTRL_V);
        assertEquals("abc", f.editor.text());
        f.type(HOME + SHIFT_RIGHT + CTRL_C);
        assertEquals("a", f.clipboard.paste());
        f.type(CTRL_X);
        assertEquals("bc", f.editor.text());
        f.type(CTRL_V + CTRL_V);
        assertEquals("aabc", f.editor.text());
    }

    @Test
    void selectAll() {
        var f = new EditorFixture(dir);
        f.type("one" + ENTER + "two");
        f.type(CTRL_A);
        assertEquals("one\ntwo", f.editor.selectedText());
        f.type(CTRL_INS);
        assertEquals("one\ntwo", f.clipboard.paste());
    }

    @Test
    void undoAndRedo() {
        var f = new EditorFixture(dir);
        f.type("abc");
        f.type(ENTER);
        f.type("def");
        assertEquals("abc\ndef", f.editor.text());
        f.type(CTRL_Z);
        assertEquals("abc\n", f.editor.text());
        f.type(CTRL_Z);
        assertEquals("abc", f.editor.text());
        f.type(CTRL_Z);
        assertEquals("", f.editor.text());
        f.type(CTRL_Z);
        assertTrue(f.status().contains("Nothing to undo"));
        f.type(CTRL_Y + CTRL_Y);
        assertEquals("abc\n", f.editor.text());
        f.type(CTRL_Y);
        f.type(CTRL_Y);
        assertEquals("abc\ndef", f.editor.text());
    }

    @Test
    void cursorMoveBreaksTypingUndoGroups() {
        var f = new EditorFixture(dir);
        f.type("abc" + LEFT + LEFT + "X");
        assertEquals("aXbc", f.editor.text());
        f.type(CTRL_Z);
        assertEquals("abc", f.editor.text());
    }

    @Test
    void overwriteMode() {
        var f = new EditorFixture(dir);
        f.type("abcdef" + HOME);
        f.type(INS);
        assertTrue(f.editor.isOverwrite());
        f.type("XY");
        assertEquals("XYcdef", f.editor.text());
        assertTrue(f.status().contains("OVR"));
        f.type(END + "Z");
        assertEquals("XYcdefZ", f.editor.text());
        f.type(INS);
        assertFalse(f.editor.isOverwrite());
        f.type(HOME + "_");
        assertEquals("_XYcdefZ", f.editor.text());
    }

    @Test
    void tabInsertsSpacesToTheNextStop() {
        var f = new EditorFixture(dir);
        f.type("ab" + TAB + "c");
        assertEquals("ab  c", f.editor.text());
        var g = new EditorFixture(dir, EditorFixture.keysWith(), EditorOptions.defaults().withNoColor(true).withUseSpaces(false));
        g.type("ab" + TAB + "c");
        assertEquals("ab\tc", g.editor.text());
    }

    @Test
    void tabAndShiftTabIndentSelectedLines() {
        var f = new EditorFixture(dir);
        f.type("one" + ENTER + "two" + ENTER + "three");
        f.type(CTRL_HOME + SHIFT_DOWN + SHIFT_DOWN + SHIFT_END);
        f.type(TAB);
        assertEquals("    one\n    two\n    three", f.editor.text());
        f.type(SHIFT_TAB);
        assertEquals("one\ntwo\nthree", f.editor.text());
        f.type(CTRL_Z);
        assertEquals("    one\n    two\n    three", f.editor.text());
    }

    @Test
    void shiftTabUnindentsTheCurrentLine() {
        var f = new EditorFixture(dir);
        f.type("      x");
        f.type(SHIFT_TAB);
        assertEquals("  x", f.editor.text());
        assertEquals(4, f.editor.column());
    }

    @Test
    void findSelectsTheMatchAndRepeatFindContinues() {
        var f = new EditorFixture(dir);
        f.type("foo bar foo baz foo");
        f.type(CTRL_HOME);
        f.type(CTRL_Q + "f");
        assertTrue(f.editor.dialogOpen());
        assertEquals("Find", f.editor.dialogTitle());
        f.type("foo" + ENTER);
        assertFalse(f.editor.dialogOpen());
        assertEquals("foo", f.editor.selectedText());
        assertEquals(3, f.editor.cursor());
        f.type(F3);
        assertEquals(11, f.editor.cursor());
        f.type(F3);
        assertEquals(19, f.editor.cursor());
        f.type(F3);
        assertEquals(3, f.editor.cursor());
        assertTrue(f.status().contains("wrapped"));
    }

    @Test
    void findOptionsAndNotFound() {
        var f = new EditorFixture(dir);
        f.type("Foo foo foobar");
        f.type(CTRL_HOME + CTRL_Q + "f");
        f.type("foo" + TAB + " " + ENTER); // Tab to Match case, Space toggles it, Enter runs OK
        assertEquals(7, f.editor.cursor());
        f.type(F3);
        assertEquals(11, f.editor.cursor());
        f.type(CTRL_Q + "f");
        f.type(BACKSPACE + BACKSPACE + BACKSPACE + "zzz" + ENTER);
        assertTrue(f.status().contains("Not found: zzz"));
    }

    @Test
    void wholeWordFind() {
        var f = new EditorFixture(dir);
        f.type("foobar foo");
        f.type(CTRL_HOME + CTRL_Q + "f");
        f.type("foo" + TAB + TAB + " " + ENTER);
        assertEquals(10, f.editor.cursor());
    }

    @Test
    void repeatFindWithoutAPreviousFindOpensTheDialog() {
        var f = new EditorFixture(dir);
        f.type(F3);
        assertEquals("Find", f.editor.dialogTitle());
    }

    @Test
    void changeReplacesOneAndAll() {
        var f = new EditorFixture(dir);
        f.type("cat dog cat bird cat");
        f.type(CTRL_HOME + CTRL_Q + "a");
        assertEquals("Change", f.editor.dialogTitle());
        f.type("cat" + TAB + "fish");
        // Find Next (the default button) keeps the dialog open
        f.type(ENTER);
        assertTrue(f.editor.dialogOpen());
        assertEquals("cat", f.editor.selectedText());
        // move focus to the buttons: Tab through check boxes
        f.type(TAB + TAB + TAB); // match case, whole word, Find Next
        f.type(RIGHT + ENTER); // Change
        assertEquals("fish dog cat bird cat", f.editor.text());
        assertEquals("cat", f.editor.selectedText());
        f.type(RIGHT + ENTER); // Change All
        assertFalse(f.editor.dialogOpen());
        assertEquals("fish dog fish bird fish", f.editor.text());
        assertTrue(f.status().contains("2 changes"));
        f.type(CTRL_Z);
        assertEquals("fish dog cat bird cat", f.editor.text());
    }

    @Test
    void goToLine() {
        var f = new EditorFixture(dir);
        f.type(lines(30).replace('\n', '\r'));
        f.type(CTRL_G);
        assertEquals("Go To Line", f.editor.dialogTitle());
        f.type(BACKSPACE + BACKSPACE + "7" + ENTER);
        assertEquals(7, f.editor.line());
        f.type(CTRL_G);
        f.type(BACKSPACE + BACKSPACE + "999" + ENTER);
        assertEquals(31, f.editor.line());
        assertTrue(f.status().contains("last line"));
        f.type(CTRL_G);
        f.type(BACKSPACE + BACKSPACE + BACKSPACE + "x" + ENTER);
        assertTrue(f.status().contains("Not a line number"));
    }

    @Test
    void escapeClearsTheSelectionAndDoesNotTypeText() {
        var f = new EditorFixture(dir);
        f.type("abc" + SHIFT_LEFT + ESC);
        assertEquals("", f.editor.selectedText());
        assertEquals("abc", f.editor.text());
    }

    @Test
    void unknownKeySequencesAreIgnored() {
        var f = new EditorFixture(dir);
        f.type("a\u001b[99~b\u0002c\u001bzd");
        assertTrue(f.editor.text().startsWith("ab"));
        assertTrue(f.editor.text().contains("c"));
    }
}
