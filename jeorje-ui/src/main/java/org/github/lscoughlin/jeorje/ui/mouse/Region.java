package org.github.lscoughlin.jeorje.ui.mouse;

/** A visible area that takes mouse events. */
public interface Region {
    /** The current screen rectangle. */
    Rect bounds();

    /** Handles one event. */
    void onMouse(RegionEvent event);
}
