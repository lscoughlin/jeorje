package org.github.lscoughlin.jeorje.ui.mouse;

import java.util.List;

/** A text selection in content coordinates. Both ends are inclusive. */
public final class Selection {
    private int anchorLine;
    private int anchorCol;
    private int headLine;
    private int headCol;
    private boolean active;

    public boolean isActive() { return active; }

    public void clear() { active = false; }

    public void start(int line, int col) {
        anchorLine = headLine = line;
        anchorCol = headCol = col;
        active = true;
    }

    public void extend(int line, int col) {
        if (!active) start(line, col);
        headLine = line;
        headCol = col;
    }

    /** Selects a range directly, for a word or a line. */
    public void select(int line, int fromCol, int toCol) {
        anchorLine = headLine = line;
        anchorCol = fromCol;
        headCol = toCol;
        active = true;
    }

    /** True when the selection covers more than the cell where it started. */
    public boolean isRange() {
        return active && (anchorLine != headLine || anchorCol != headCol);
    }

    /** The selected text without colors. The lines are joined with a line feed. */
    public String text(List<? extends CharSequence> lines) {
        if (!active || lines.isEmpty()) return "";
        var aFirst = anchorLine < headLine || (anchorLine == headLine && anchorCol <= headCol);
        var sl = aFirst ? anchorLine : headLine;
        var sc = aFirst ? anchorCol : headCol;
        var el = aFirst ? headLine : anchorLine;
        var ec = aFirst ? headCol : anchorCol;
        sl = Math.clamp(sl, 0, lines.size() - 1);
        el = Math.clamp(el, 0, lines.size() - 1);
        var sb = new StringBuilder();
        for (var l = sl; l <= el; l++) {
            var s = lines.get(l).toString();
            var from = l == sl ? Math.clamp(sc, 0, s.length()) : 0;
            var to = l == el ? Math.clamp(ec + 1, 0, s.length()) : s.length();
            if (l > sl) sb.append('\n');
            if (to > from) sb.append(s, from, to);
        }
        return sb.toString();
    }

    /** The cells of one content line that the selection covers, as {from, toExclusive}, or null. */
    public int[] columnsOf(int line, int lineLength) {
        if (!active) return null;
        var aFirst = anchorLine < headLine || (anchorLine == headLine && anchorCol <= headCol);
        var sl = aFirst ? anchorLine : headLine;
        var sc = aFirst ? anchorCol : headCol;
        var el = aFirst ? headLine : anchorLine;
        var ec = aFirst ? headCol : anchorCol;
        if (line < sl || line > el) return null;
        var from = line == sl ? Math.clamp(sc, 0, lineLength) : 0;
        var to = line == el ? Math.clamp(ec + 1, 0, lineLength) : lineLength;
        return to > from ? new int[] {from, to} : null;
    }
}
