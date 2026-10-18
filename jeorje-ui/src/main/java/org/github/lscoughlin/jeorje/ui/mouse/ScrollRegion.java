package org.github.lscoughlin.jeorje.ui.mouse;

/** A region that scrolls with the wheel. A wheel step scrolls {@code wheelLines} lines. Shift scrolls sideways. */
public class ScrollRegion implements Region {
    private final Rect bounds;
    private final ScrollModel model;

    public ScrollRegion(Rect bounds, ScrollModel model) {
        this.bounds = bounds;
        this.model = model;
    }

    @Override
    public Rect bounds() { return bounds; }

    public ScrollModel model() { return model; }

    @Override
    public void onMouse(RegionEvent event) {
        if (event.isWheel()) model.wheel(event.button(), event.shift());
    }
}
