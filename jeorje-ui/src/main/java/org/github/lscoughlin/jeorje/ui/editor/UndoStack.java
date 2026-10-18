package org.github.lscoughlin.jeorje.ui.editor;

import java.util.ArrayList;
import java.util.List;

/** The undo and redo history of a document. Typing and deleting in a row merge into one step. */
public final class UndoStack {

    /**
     * One change of the text.
     *
     * @param pos the offset of the change
     * @param removed the text that the change removed
     * @param inserted the text that the change inserted
     * @param cursorBefore the cursor before the change
     * @param cursorAfter the cursor after the change
     */
    public record Edit(int pos, String removed, String inserted, int cursorBefore, int cursorAfter) { }

    /** How a change can merge with the change before it. */
    public enum Kind { NONE, TYPING, BACKSPACE, DELETE }

    private final List<Edit> undo = new ArrayList<>();
    private final List<Edit> redo = new ArrayList<>();
    private Kind lastKind = Kind.NONE;
    private int cleanDepth;

    /** Records an applied change. */
    public void record(Edit edit, Kind kind) {
        if (cleanDepth > undo.size()) {
            cleanDepth = -1;
        }
        if (!redo.isEmpty() && cleanDepth > undo.size()) {
            cleanDepth = -1;
        }
        redo.clear();
        if (kind != Kind.NONE && kind == lastKind && !undo.isEmpty() && undo.size() != cleanDepth) {
            var merged = merge(undo.getLast(), edit, kind);
            if (merged != null) {
                undo.set(undo.size() - 1, merged);
                return;
            }
        }
        undo.add(edit);
        lastKind = kind;
    }

    private static Edit merge(Edit last, Edit next, Kind kind) {
        return switch (kind) {
            case TYPING -> last.removed().isEmpty() && next.removed().isEmpty()
                    && next.pos() == last.pos() + last.inserted().length()
                    ? new Edit(last.pos(), "", last.inserted() + next.inserted(), last.cursorBefore(),
                            next.cursorAfter())
                    : null;
            case BACKSPACE -> last.inserted().isEmpty() && next.inserted().isEmpty()
                    && next.pos() + next.removed().length() == last.pos()
                    ? new Edit(next.pos(), next.removed() + last.removed(), "", last.cursorBefore(),
                            next.cursorAfter())
                    : null;
            case DELETE -> last.inserted().isEmpty() && next.inserted().isEmpty() && next.pos() == last.pos()
                    ? new Edit(last.pos(), last.removed() + next.removed(), "", last.cursorBefore(),
                            next.cursorAfter())
                    : null;
            case NONE -> null;
        };
    }

    /** Stops the merge of the next change with the last one. Call it after a cursor move. */
    public void breakMerge() {
        lastKind = Kind.NONE;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Takes the last change from the undo list and puts it on the redo list. */
    public Edit popUndo() {
        lastKind = Kind.NONE;
        var edit = undo.removeLast();
        redo.add(edit);
        return edit;
    }

    /** Takes the last undone change from the redo list and puts it on the undo list. */
    public Edit popRedo() {
        lastKind = Kind.NONE;
        var edit = redo.removeLast();
        undo.add(edit);
        return edit;
    }

    /** Marks the current state as saved. */
    public void markClean() {
        lastKind = Kind.NONE;
        cleanDepth = undo.size();
    }

    /** True when the history is at the state that {@link #markClean()} marked. */
    public boolean isClean() {
        return cleanDepth == undo.size();
    }

    /** Clears both lists. */
    public void clear() {
        undo.clear();
        redo.clear();
        lastKind = Kind.NONE;
        cleanDepth = 0;
    }
}
