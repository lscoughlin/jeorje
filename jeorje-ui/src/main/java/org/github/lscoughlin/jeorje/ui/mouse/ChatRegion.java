package org.github.lscoughlin.jeorje.ui.mouse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The chat view as a mouse region. It scrolls with the wheel, selects text with a drag, a double click, and a
 * triple click, copies the selection, and runs the click handlers of the lines.
 */
public final class ChatRegion implements Region {
    private final Rect bounds;
    private final ChatScroll scroll;
    private final Supplier<List<? extends CharSequence>> content;
    private final Selection selection = new Selection();
    private final Clipboard clipboard;
    private final boolean copyOnSelect;
    private final Consumer<String> status;
    private final List<LineClick> clickHandlers = new ArrayList<>();
    private boolean dragged;

    /**
     * @param content the content lines, with or without colors, that the selection counts in
     */
    public ChatRegion(
            Rect bounds,
            ChatScroll scroll,
            Supplier<List<? extends CharSequence>> content,
            Clipboard clipboard,
            MouseConfig config,
            Consumer<String> status) {
        this.bounds = bounds;
        this.scroll = scroll;
        this.content = content;
        this.clipboard = clipboard;
        this.copyOnSelect = config.copyOnSelect();
        this.status = status;
    }

    @Override
    public Rect bounds() { return bounds; }

    public Selection selection() { return selection; }

    public ChatRegion addClickHandler(LineClick handler) {
        clickHandlers.add(handler);
        return this;
    }

    /** Copies the selection (the {@code Ctrl-X c} action). Returns the copied text. */
    public String copySelection() {
        var text = selection.text(content.get());
        if (!text.isEmpty()) status.accept(clipboard.copy(text).status());
        return text;
    }

    @Override
    public void onMouse(RegionEvent e) {
        switch (e.type()) {
            case Wheel -> scroll.model().wheel(e.button(), e.shift());
            case Pressed -> press(e);
            case Dragged -> {
                if (!selection.isActive()) return;
                dragged = true;
                selection.extend(lineOf(e.y()), colOf(e.x()));
            }
            case Released -> release(e);
            default -> { }
        }
    }

    private int lineOf(int y) {
        var lines = content.get();
        return Math.clamp(scroll.model().offset() + Math.clamp(y, 0, bounds.height() - 1), 0, Math.max(0, lines.size() - 1));
    }

    private int colOf(int x) {
        return Math.clamp(x, 0, bounds.width() - 1);
    }

    private void press(RegionEvent e) {
        if (e.button() != org.jline.terminal.MouseEvent.Button.Button1) return;
        dragged = false;
        var lines = content.get();
        if (lines.isEmpty()) return;
        var line = lineOf(e.y());
        var col = colOf(e.x());
        var text = lines.get(line).toString();
        switch (e.clicks()) {
            case 2 -> {
                var range = wordAt(text, col);
                selection.select(line, range[0], range[1]);
                dragged = true;
            }
            case 3 -> {
                selection.select(line, 0, Math.max(0, text.length() - 1));
                dragged = true;
            }
            default -> selection.start(line, col);
        }
    }

    private void release(RegionEvent e) {
        var lines = content.get();
        if (!dragged && e.clicks() <= 1) {
            selection.clear();
            if (lines.isEmpty()) return;
            var line = lineOf(e.y());
            var col = colOf(e.x());
            var text = lines.get(line).toString();
            for (var h : clickHandlers) {
                if (h.onClick(line, col, text)) return;
            }
            return;
        }
        if (copyOnSelect && selection.isActive()) copySelection();
    }

    static int[] wordAt(String text, int col) {
        if (text.isEmpty()) return new int[] {0, 0};
        var c = Math.clamp(col, 0, text.length() - 1);
        var kind = kind(text.charAt(c));
        var from = c;
        var to = c;
        while (from > 0 && kind(text.charAt(from - 1)) == kind) from--;
        while (to < text.length() - 1 && kind(text.charAt(to + 1)) == kind) to++;
        return new int[] {from, to};
    }

    private static int kind(char ch) {
        if (Character.isLetterOrDigit(ch) || ch == '_') return 0;
        return Character.isWhitespace(ch) ? 1 : 2;
    }
}
