package org.github.lscoughlin.jeorje.testkit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A {@link Clock} that moves only when the test moves it. */
public final class ManualClock extends Clock {
    private volatile Instant now;
    private final ZoneId zone;

    public ManualClock() {
        this(Instant.parse("2026-01-01T00:00:00Z"));
    }

    public ManualClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    private ManualClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public synchronized void advance(Duration duration) { now = now.plus(duration); }

    public void set(Instant instant) { now = instant; }

    @Override
    public ZoneId getZone() { return zone; }

    @Override
    public Clock withZone(ZoneId zone) {
        return new ManualClock(now, zone);
    }

    @Override
    public Instant instant() { return now; }
}
