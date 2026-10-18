package org.github.lscoughlin.jeorje.ui.mouse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The scroll behavior of the chat view: follow mode, the notice for new lines, and loading of older lines.
 * The lines are plain text lines. The class is thread safe.
 */
public final class ChatScroll {
    public static final int MAX_LINES = 10_000;
    public static final int BLOCK = 500;

    private final ScrollModel model;
    private final OlderLines older;
    private final List<String> lines = new ArrayList<>();
    private int firstIndex;
    private boolean following = true;
    private int newLines;

    /** @param firstIndex the transcript line number of the first line that is already in the view */
    public ChatScroll(ScrollModel model, OlderLines older, int firstIndex) {
        this.model = model;
        this.older = older;
        this.firstIndex = firstIndex;
        model.onScroll(this::userScrolled);
        model.onOverscrollTop(this::loadOlder);
    }

    public ScrollModel model() { return model; }

    public synchronized List<String> lines() { return List.copyOf(lines); }

    public synchronized int firstIndex() { return firstIndex; }

    public synchronized boolean following() { return following; }

    public synchronized int newLines() { return newLines; }

    public synchronized void setViewport(int height) {
        model.setContent(lines.size(), height);
        if (following) model.jumpToBottom();
    }

    /** Adds lines at the bottom. The view follows, or counts the lines for the notice. */
    public synchronized void append(List<String> added) {
        if (added.isEmpty()) return;
        lines.addAll(added);
        var removed = Math.max(0, lines.size() - MAX_LINES);
        if (removed > 0) {
            lines.subList(0, removed).clear();
            firstIndex += removed;
        }
        model.setContent(lines.size(), model.viewport());
        if (following) {
            model.jumpToBottom();
        } else {
            newLines += added.size();
            model.shift(-removed);
        }
    }

    public void append(String line) { append(List.of(line)); }

    /** The status text, or empty when the view follows. */
    public synchronized Optional<String> notice() {
        if (following || newLines == 0) return Optional.empty();
        return Optional.of(newLines + " new lines - Ctrl-X b or click here to follow");
    }

    /** The {@code follow} action and a click on the notice: scroll to the bottom and follow again. */
    public synchronized void follow() {
        following = true;
        newLines = 0;
        model.jumpToBottom();
    }

    /** Loads one block of older lines above the first line. Returns the number of lines that it loaded. */
    public synchronized int loadOlder() {
        if (firstIndex <= 0 || older == null) return 0;
        var block = older.before(firstIndex, BLOCK);
        if (block.isEmpty()) return 0;
        lines.addAll(0, block);
        firstIndex = Math.max(0, firstIndex - block.size());
        model.setContent(lines.size(), model.viewport());
        model.shift(block.size());
        return block.size();
    }

    private void loadOlder(int missing) {
        var loaded = loadOlder();
        if (loaded > 0) model.scrollBy(-Math.min(missing, loaded));
    }

    private synchronized void userScrolled(int delta) {
        if (model.atBottom()) {
            following = true;
            newLines = 0;
        } else if (delta < 0) {
            following = false;
        }
    }
}
