package org.github.lscoughlin.jeorje.ui.mouse;

/** A screen rectangle in cells. The cell coordinates are the ones that JLine reports in a mouse event. */
public record Rect(int x, int y, int width, int height) {
    public boolean contains(int px, int py) {
        return px >= x && px < x + width && py >= y && py < y + height;
    }
}
