package org.github.lscoughlin.jeorje.ui.mouse;

import java.util.function.LongSupplier;

/** Counts clicks. JLine has no click count. A press on the same cell within 400 ms adds one, up to three. */
public final class ClickCounter {
    public static final long WINDOW_MS = 400;

    private final LongSupplier clockMillis;
    private int x = -1;
    private int y = -1;
    private long last = Long.MIN_VALUE / 2;
    private int count;

    public ClickCounter(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /** Registers a press and returns its click count. */
    public synchronized int press(int px, int py) {
        var now = clockMillis.getAsLong();
        if (px == x && py == y && now - last <= WINDOW_MS && count < 3) count++;
        else count = 1;
        x = px;
        y = py;
        last = now;
        return count;
    }

    /** The count of the latest press. */
    public synchronized int current() {
        return count;
    }
}
