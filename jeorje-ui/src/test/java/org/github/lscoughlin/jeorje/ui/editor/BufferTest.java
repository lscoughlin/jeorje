package org.github.lscoughlin.jeorje.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BufferTest {

    @Test
    void insertAndDeleteKeepTheText() {
        var buffer = new TextBuffer("hello world");
        buffer.insert(5, ",");
        buffer.delete(0, 1);
        buffer.insert(0, "J");
        assertEquals("Jello, world", buffer.text());
        assertEquals(12, buffer.length());
    }

    @Test
    void gapMovesBackAndForthAndGrows() {
        var buffer = new TextBuffer();
        for (var i = 0; i < 1000; i++) {
            buffer.insert(buffer.length() / 2, "ab");
        }
        assertEquals(2000, buffer.length());
        buffer.insert(0, "start:");
        buffer.insert(buffer.length(), ":end");
        assertTrue(buffer.text().startsWith("start:"));
        assertTrue(buffer.text().endsWith(":end"));
    }

    @Test
    void lineIndexFollowsChanges() {
        var buffer = new TextBuffer("one\ntwo\nthree");
        assertEquals(3, buffer.lineCount());
        assertEquals("two", buffer.line(1));
        assertEquals(4, buffer.lineStart(1));
        assertEquals(7, buffer.lineEnd(1));
        assertEquals(2, buffer.lineOf(9));
        buffer.insert(3, "\nextra");
        assertEquals(4, buffer.lineCount());
        assertEquals("extra", buffer.line(1));
        buffer.delete(3, 6);
        assertEquals(3, buffer.lineCount());
    }

    @Test
    void finalNewlineAddsAnEmptyLastLine() {
        var buffer = new TextBuffer("a\n");
        assertEquals(2, buffer.lineCount());
        assertEquals("", buffer.line(1));
        assertEquals(1, new TextBuffer("").lineCount());
    }

    @Test
    void rangeErrorsAreReported() {
        var buffer = new TextBuffer("abc");
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.insert(4, "x"));
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.delete(2, 5));
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.charAt(3));
    }

    @Test
    void versionChangesWithEachEdit() {
        var buffer = new TextBuffer("abc");
        var v = buffer.version();
        buffer.insert(1, "x");
        assertTrue(buffer.version() > v);
    }

    @Test
    void undoStackMergesTypingAndTracksCleanState() {
        var doc = new EditorDocument("", null);
        doc.undo.markClean();
        assertFalse(doc.isModified());
        doc.replace(0, 0, "a", 0, 1, UndoStack.Kind.TYPING);
        doc.replace(1, 0, "b", 1, 2, UndoStack.Kind.TYPING);
        doc.replace(2, 0, "c", 2, 3, UndoStack.Kind.TYPING);
        assertTrue(doc.isModified());
        assertEquals("abc", doc.buffer.text());
        assertEquals(0, doc.undo());
        assertEquals("", doc.buffer.text());
        assertFalse(doc.isModified());
        assertEquals(3, doc.redo());
        assertEquals("abc", doc.buffer.text());
        assertEquals(-1, doc.redo());
    }

    @Test
    void backspaceAndDeleteMerge() {
        var doc = new EditorDocument("abcdef", null);
        doc.undo.markClean();
        doc.replace(5, 1, "", 6, 5, UndoStack.Kind.BACKSPACE);
        doc.replace(4, 1, "", 5, 4, UndoStack.Kind.BACKSPACE);
        assertEquals("abcd", doc.buffer.text());
        assertEquals(6, doc.undo());
        assertEquals("abcdef", doc.buffer.text());
        doc.replace(0, 1, "", 0, 0, UndoStack.Kind.DELETE);
        doc.replace(0, 1, "", 0, 0, UndoStack.Kind.DELETE);
        assertEquals("cdef", doc.buffer.text());
        doc.undo();
        assertEquals("abcdef", doc.buffer.text());
    }

    @Test
    void newEditAfterUndoClearsRedoAndCleanMarker() {
        var doc = new EditorDocument("", null);
        doc.replace(0, 0, "a", 0, 1, UndoStack.Kind.NONE);
        doc.undo.markClean();
        doc.undo();
        doc.replace(0, 0, "b", 0, 1, UndoStack.Kind.NONE);
        assertTrue(doc.isModified());
        assertFalse(doc.undo.canRedo());
    }
}
