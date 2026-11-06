package org.github.lscoughlin.jeorje.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ActivityLineTest {
    private static final Instant T0 = Instant.parse("2026-01-02T03:04:05Z");

    private static Clock fixed() { return Clock.fixed(T0, ZoneOffset.UTC); }

    private static String text(ActivityLine line, int width, long seconds) {
        return line.render(width, T0.plus(Duration.ofSeconds(seconds))).toString();
    }

    @Test
    void theRowAtZeroTwelveAndSeventyFiveSeconds() {
        var line = new ActivityLine(fixed(), () -> 0, false);
        assertNull(line.render(80, T0));
        line.update(true, 8400, 1200);
        assertEquals("⠋ Thinking... (0s | ↑ 8.4k tokens | ↓ 1.2k tokens)", text(line, 80, 0));
        assertEquals("⠋ Reasoning... (12s | ↑ 8.4k tokens | ↓ 1.2k tokens)", text(line, 80, 12));
        assertEquals("⠋ Weighing... (1m 15s | ↑ 8.4k tokens | ↓ 1.2k tokens)", text(line, 80, 75));
    }

    @Test
    void theCountFormatter() {
        assertEquals("999", ActivityLine.count(999));
        assertEquals("1.0k", ActivityLine.count(1000));
        assertEquals("12.3k", ActivityLine.count(12345));
        assertEquals("0", ActivityLine.count(0));
    }

    @Test
    void theElapsedFormatter() {
        assertEquals("59s", ActivityLine.elapsed(Duration.ofSeconds(59)));
        assertEquals("1m 0s", ActivityLine.elapsed(Duration.ofSeconds(60)));
        assertEquals("1m 15s", ActivityLine.elapsed(Duration.ofSeconds(75)));
    }

    @Test
    void theWordChangesAfterFourSeconds() {
        var line = new ActivityLine(fixed(), () -> 0, false);
        line.update(true, 1, 1);
        assertTrue(text(line, 80, 3).contains("Thinking..."));
        assertTrue(text(line, 80, 4).contains("Pondering..."));
        assertTrue(text(line, 80, 8).contains("Musing..."));
        // The list wraps.
        assertTrue(text(line, 80, 40).contains("Thinking..."));
    }

    @Test
    void aNewCallStartsAtTheNextWord() {
        var line = ActivityLine.create(fixed(), false);
        line.update(true, 1, 1);
        assertTrue(text(line, 80, 0).contains("Thinking..."));
        line.update(false, 1, 1);
        assertNull(line.render(80, T0));
        line.update(true, 1, 1);
        assertTrue(text(line, 80, 0).contains("Pondering..."));
        // An update while active keeps the word and the timer.
        line.update(true, 5, 5);
        assertTrue(text(line, 80, 0).contains("Pondering..."));
    }

    @Test
    void theSpinnerStepsAndPlainModeUsesAscii() {
        var line = new ActivityLine(fixed(), () -> 0, false);
        line.update(true, 1, 1);
        line.tick();
        assertTrue(text(line, 80, 0).startsWith("⠙ "));
        var plain = new ActivityLine(fixed(), () -> 0, true);
        plain.update(true, 1, 1);
        assertTrue(text(plain, 80, 0).startsWith("| "));
        plain.tick();
        assertTrue(text(plain, 80, 0).startsWith("/ "));
        for (var i = 0; i < 3; i++) plain.tick();
        assertTrue(text(plain, 80, 0).startsWith("| "));
    }

    @Test
    void theRowIsCutAtANarrowWidthAndNeverWraps() {
        var line = new ActivityLine(fixed(), () -> 0, false);
        line.update(true, 8400, 1200);
        var cut = text(line, 20, 0);
        assertEquals(20, cut.length());
        assertTrue(cut.endsWith("…"));
        assertFalse(text(line, 80, 0).endsWith("…"));
    }

    @Test
    void theClockStartsTheTimerWhenTheCallStarts() {
        var now = new Instant[] {T0};
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }

            @Override public Clock withZone(ZoneId zone) { return this; }

            @Override public Instant instant() { return now[0]; }
        };
        var line = new ActivityLine(clock, () -> 0, false);
        now[0] = T0.plusSeconds(100);
        line.update(true, 1, 1);
        now[0] = T0.plusSeconds(130);
        assertTrue(line.render(80, clock.instant()).toString().contains("(30s"));
    }
}
