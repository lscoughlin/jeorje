package org.github.lscoughlin.jeorje.ui.mouse;

import java.util.function.IntConsumer;
import org.jline.terminal.MouseEvent;

/** The scroll position of one region: a vertical offset and a sideways offset. */
public final class ScrollModel {
    private int total;
    private int viewport;
    private int offset;
    private int hTotal;
    private int hViewport;
    private int hOffset;
    private final int wheelLines;
    private IntConsumer onScroll = d -> { };
    private IntConsumer onOverscrollTop = n -> { };

    public ScrollModel(int wheelLines) {
        this.wheelLines = wheelLines;
    }

    /** Sets a listener for user scrolls. It gets the requested line change (negative is up). */
    public synchronized void onScroll(IntConsumer listener) {
        this.onScroll = listener;
    }

    /** Sets a listener for a scroll above the first line. It gets the number of missing lines. */
    public synchronized void onOverscrollTop(IntConsumer listener) {
        this.onOverscrollTop = listener;
    }

    /** Sets the content height and the viewport height. The offset stays inside the range. */
    public synchronized void setContent(int totalLines, int viewportLines) {
        this.total = Math.max(0, totalLines);
        this.viewport = Math.max(0, viewportLines);
        offset = Math.clamp(offset, 0, maxOffset());
    }

    /** Sets the content width and the viewport width for sideways scrolling. */
    public synchronized void setHorizontal(int totalColumns, int viewportColumns) {
        this.hTotal = Math.max(0, totalColumns);
        this.hViewport = Math.max(0, viewportColumns);
        hOffset = Math.clamp(hOffset, 0, maxHOffset());
    }

    public synchronized int total() { return total; }

    public synchronized int viewport() { return viewport; }

    public synchronized int offset() { return offset; }

    public synchronized int hOffset() { return hOffset; }

    public synchronized int maxOffset() { return Math.max(0, total - viewport); }

    private int maxHOffset() { return Math.max(0, hTotal - hViewport); }

    public synchronized boolean atTop() { return offset == 0; }

    public synchronized boolean atBottom() { return offset >= maxOffset(); }

    /** Moves the offset without a listener call. */
    public synchronized void shift(int delta) {
        offset = Math.clamp((long) offset + delta, 0, maxOffset());
    }

    /** Moves to the last line without a listener call. */
    public synchronized void jumpToBottom() { offset = maxOffset(); }

    /** Scrolls by lines. Negative is up. */
    public synchronized void scrollBy(int delta) {
        if (delta == 0) return;
        var target = (long) offset + delta;
        offset = Math.clamp(target, 0, maxOffset());
        if (target < 0) onOverscrollTop.accept((int) -target);
        onScroll.accept(delta);
    }

    public synchronized void scrollTo(int line) { scrollBy(Math.clamp(line, 0, maxOffset()) - offset); }

    /** Scrolls one page. Negative pages go up. */
    public synchronized void page(int pages) { scrollBy(pages * Math.max(1, viewport - 1)); }

    public synchronized void scrollSideways(int columns) {
        hOffset = Math.clamp((long) hOffset + columns, 0, maxHOffset());
    }

    /** Applies a wheel event. Returns true when it was a wheel event. */
    public synchronized boolean wheel(MouseEvent.Button button, boolean shift) {
        var sign =
                switch (button) {
                    case WheelUp -> -1;
                    case WheelDown -> 1;
                    default -> 0;
                };
        if (sign == 0) return false;
        if (shift) scrollSideways(sign * wheelLines);
        else scrollBy(sign * wheelLines);
        return true;
    }
}
