package org.github.lscoughlin.jeorje.ui.mouse;

import java.util.ArrayList;
import java.util.List;
import org.jline.terminal.MouseEvent;

/**
 * A vertical scroll bar of one column. Row 0 is the up arrow. The last row is the down arrow. The rows between
 * them are the track with the thumb.
 */
public final class ScrollBar implements Region {
    public static final String UP = "▲";
    public static final String DOWN = "▼";
    public static final String THUMB = "█";
    public static final String TRACK = "░";

    private final Rect bounds;
    private final ScrollModel model;
    private boolean dragging;
    private int grab;

    public ScrollBar(Rect bounds, ScrollModel model) {
        this.bounds = bounds;
        this.model = model;
    }

    @Override
    public Rect bounds() { return bounds; }

    private int trackLength() { return Math.max(0, bounds.height() - 2); }

    /** The size of the thumb in rows. */
    public int thumbSize() {
        var track = trackLength();
        if (track == 0) return 0;
        if (model.total() <= 0 || model.total() <= model.viewport()) return track;
        return Math.clamp((long) track * model.viewport() / model.total(), 1, track);
    }

    /** The row of the top of the thumb, counted from the start of the track. */
    public int thumbPosition() {
        var free = trackLength() - thumbSize();
        var max = model.maxOffset();
        if (free <= 0 || max <= 0) return 0;
        return (int) Math.round((double) free * model.offset() / max);
    }

    /** The rows of the bar, top to bottom. */
    public List<String> render() {
        var out = new ArrayList<String>();
        for (var row = 0; row < bounds.height(); row++) {
            if (row == 0) out.add(UP);
            else if (row == bounds.height() - 1) out.add(DOWN);
            else {
                var t = row - 1;
                out.add(t >= thumbPosition() && t < thumbPosition() + thumbSize() ? THUMB : TRACK);
            }
        }
        return out;
    }

    @Override
    public void onMouse(RegionEvent e) {
        switch (e.type()) {
            case Pressed -> {
                if (e.button() != MouseEvent.Button.Button1) return;
                press(e.y());
            }
            case Dragged -> {
                if (dragging) dragTo(e.y());
            }
            case Released -> dragging = false;
            case Wheel -> model.wheel(e.button(), false);
            default -> { }
        }
    }

    private void press(int row) {
        var last = bounds.height() - 1;
        if (row <= 0) {
            model.scrollBy(-1);
        } else if (row >= last) {
            model.scrollBy(1);
        } else {
            var t = row - 1;
            var pos = thumbPosition();
            if (t < pos) model.page(-1);
            else if (t >= pos + thumbSize()) model.page(1);
            else {
                dragging = true;
                grab = t - pos;
            }
        }
    }

    private void dragTo(int row) {
        var free = trackLength() - thumbSize();
        if (free <= 0) return;
        var pos = Math.clamp(row - 1 - grab, 0, free);
        model.scrollTo((int) Math.round((double) pos * model.maxOffset() / free));
    }
}
