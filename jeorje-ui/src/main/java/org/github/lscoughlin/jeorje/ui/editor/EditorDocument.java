package org.github.lscoughlin.jeorje.ui.editor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.github.lscoughlin.jeorje.core.state.FileState;
import org.github.lscoughlin.jeorje.ui.editor.markdown.InjectionRule;
import org.github.lscoughlin.jeorje.ui.syntax.HighlightService;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxInfo;
import org.jline.utils.AttributedString;

/** The text of one file with its undo history, its disk state, and its syntax. Windows share a document. */
final class EditorDocument {

    /** A window that must follow the changes of the text. */
    interface Listener {
        void textChanged(int pos, int removedLength, int insertedLength);
    }

    final TextBuffer buffer;
    final UndoStack undo = new UndoStack();
    private final List<Listener> listeners = new ArrayList<>();
    private final HighlightService highlight;

    Path path;
    String eol = "\n";
    /** The state of the file on disk the last time the editor read or wrote it, or null for a new file. */
    FileState diskState;
    /** True when the file on disk differs from {@link #diskState}. */
    volatile boolean diskChanged;
    long bytes;
    /** The syntax that the user chose, or null for the automatic choice. */
    SyntaxInfo chosenSyntax;
    /** The syntax in use, or null for plain text. */
    SyntaxInfo syntax;
    private final Function<SyntaxInfo, InjectionRule> injections;
    private HighlightService.View view;
    /** The injection rule for the syntax in use, or null. */
    private InjectionRule rule;

    EditorDocument(String text, HighlightService highlight) {
        this(text, highlight, syntax -> null);
    }

    EditorDocument(String text, HighlightService highlight, Function<SyntaxInfo, InjectionRule> injections) {
        this.buffer = new TextBuffer(text);
        this.highlight = highlight;
        this.injections = injections;
    }

    void addListener(Listener listener) {
        listeners.add(listener);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    boolean isModified() {
        return !undo.isClean();
    }

    String displayName() {
        return path == null ? "Untitled" : path.getFileName().toString();
    }

    /** Applies a change to the text, records it, and tells the windows. */
    void replace(int pos, int removeLength, String insert, int cursorBefore, int cursorAfter, UndoStack.Kind kind) {
        var removed = buffer.substring(pos, pos + removeLength);
        if (removed.isEmpty() && insert.isEmpty()) {
            return;
        }
        apply(pos, removeLength, insert);
        undo.record(new UndoStack.Edit(pos, removed, insert, cursorBefore, cursorAfter), kind);
    }

    private void apply(int pos, int removeLength, String insert) {
        var line = buffer.lineOf(pos);
        var lastRemoved = buffer.lineOf(pos + removeLength);
        buffer.delete(pos, removeLength);
        buffer.insert(pos, insert);
        if (rule != null) {
            var lastInserted = buffer.lineOf(pos + insert.length());
            var lines = new ArrayList<String>();
            for (var i = line; i <= lastInserted; i++) {
                lines.add(buffer.line(i));
            }
            rule.replace(line, lastRemoved - line + 1, lines);
        }
        if (view != null) {
            view.invalidateFrom(line);
            view.setLineCount(buffer.lineCount());
        }
        bytes = 0;
        for (var listener : List.copyOf(listeners)) {
            listener.textChanged(pos, removeLength, insert.length());
        }
    }

    /** Undoes one change. Returns the cursor, or -1 when there is nothing to undo. */
    int undo() {
        if (!undo.canUndo()) {
            return -1;
        }
        var edit = undo.popUndo();
        apply(edit.pos(), edit.inserted().length(), edit.removed());
        return edit.cursorBefore();
    }

    /** Redoes one change. Returns the cursor, or -1 when there is nothing to redo. */
    int redo() {
        if (!undo.canRedo()) {
            return -1;
        }
        var edit = undo.popRedo();
        apply(edit.pos(), edit.removed().length(), edit.inserted());
        return edit.cursorAfter();
    }

    /** Replaces the whole text, for a reload. The history starts new. */
    void reset(String text) {
        var length = buffer.length();
        buffer.delete(0, length);
        buffer.insert(0, text);
        undo.clear();
        openView();
        for (var listener : List.copyOf(listeners)) {
            listener.textChanged(0, length, text.length());
        }
    }

    /** Opens the highlight view again. Call it after a change of the syntax or the options. */
    void openView() {
        view = highlight == null
                ? null
                : highlight.open(syntax, buffer.lineCount(), buffer::line, Math.max(bytes, buffer.length()));
        rule = null;
        if (syntax != null && (view == null || view.isColored())) {
            var candidate = injections.apply(syntax);
            if (candidate != null && candidate.appliesTo(syntax)) {
                var lines = new ArrayList<String>();
                for (var i = 0; i < buffer.lineCount(); i++) {
                    lines.add(buffer.line(i));
                }
                candidate.load(lines);
                rule = candidate;
            }
        }
    }

    /** True when an injection rule draws the lines. */
    boolean hasInjection() {
        return rule != null;
    }

    /** The notices of the highlight service, for example "Syntax colors are off". */
    List<String> takeNotices() {
        return view == null ? List.of() : view.takeNotices();
    }

    boolean isColored() {
        return view != null && view.isColored();
    }

    /** The styled lines {@code first..last}. Without a highlight service, the lines have no style. */
    List<AttributedString> styledLines(int first, int last) {
        if (rule != null) {
            return rule.lines(first, last);
        }
        if (view != null) {
            return view.lines(first, last);
        }
        var out = new ArrayList<AttributedString>();
        for (var i = Math.max(0, first); i <= Math.min(last, buffer.lineCount() - 1); i++) {
            out.add(new AttributedString(buffer.line(i)));
        }
        return out;
    }

    void forEachNotice(Consumer<String> consumer) {
        takeNotices().forEach(consumer);
    }
}
