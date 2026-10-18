package org.github.lscoughlin.jeorje.ui.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

class HighlightServiceTest {

    private static final SyntaxInfo SYNTAX = new SyntaxInfo("Fake", Path.of("fake.nanorc"), List.of());

    /** Marks each highlighted line with the call number. Records resets and the lines it saw. */
    static final class Recorder implements LineHighlighter {
        final List<String> seen = new ArrayList<>();
        int resets;
        final AtomicLong clock;
        final long costNanos;

        Recorder(AtomicLong clock, long costNanos) {
            this.clock = clock;
            this.costNanos = costNanos;
        }

        @Override
        public AttributedString highlight(String line) {
            seen.add(line);
            clock.addAndGet(costNanos);
            return new AttributedStringBuilder().style(AttributedStyle.DEFAULT.bold()).append(line)
                    .toAttributedString();
        }

        @Override
        public void reset() {
            resets++;
        }
    }

    private final AtomicLong clock = new AtomicLong();

    private static IntFunction<String> lines() {
        return i -> "line" + i;
    }

    private HighlightService service(Recorder recorder, HighlightOptions options) {
        return new HighlightService(s -> recorder, options, clock::get);
    }

    private static HighlightOptions opts() {
        return new HighlightOptions(true, 10, 2000, 200, HighlightOptions.DEFAULT_MAX_BYTES, false);
    }

    @Test
    void scrollingDownContinuesWithoutReplayAndScrollingUpUsesCache() {
        var rec = new Recorder(clock, 0);
        var view = service(rec, opts()).open(SYNTAX, 1000, lines(), 10);
        view.lines(0, 9);
        assertEquals(1, rec.resets);
        assertEquals(10, rec.seen.size());
        var down = view.lines(5, 14);
        assertEquals(1, rec.resets, "no replay when scrolling down");
        assertEquals(15, rec.seen.size());
        assertEquals("line14", down.getLast().toString());
        view.lines(0, 4);
        assertEquals(15, rec.seen.size(), "scrolling up uses the cache");
        assertFalse(view.lastRequestReplayed());
    }

    @Test
    void jumpReplaysFromFirstVisibleMinusReplayLines() {
        var rec = new Recorder(clock, 0);
        var view = service(rec, opts()).open(SYNTAX, 1000, lines(), 10);
        view.lines(0, 4);
        rec.seen.clear();
        var out = view.lines(500, 504);
        assertEquals(2, rec.resets);
        assertEquals(490, view.replayStart());
        assertEquals("line490", rec.seen.getFirst());
        assertEquals("line504", rec.seen.getLast());
        assertEquals(15, rec.seen.size(), "the replay is limited to replayLines above the view");
        assertEquals(5, out.size());
        assertTrue(view.lastRequestReplayed());
    }

    @Test
    void replayStartsAtZeroNearTheTop() {
        var rec = new Recorder(clock, 0);
        var view = service(rec, opts()).open(SYNTAX, 1000, lines(), 10);
        view.lines(5, 6);
        assertEquals(0, view.replayStart());
        assertEquals("line0", rec.seen.getFirst());
    }

    @Test
    void scrollingAboveReplayStartReplaysAgain() {
        var rec = new Recorder(clock, 0);
        var view = service(rec, opts()).open(SYNTAX, 1000, lines(), 10);
        view.lines(500, 504);
        view.lines(100, 104);
        assertEquals(2, rec.resets);
        assertEquals(90, view.replayStart());
    }

    @Test
    void invalidateFromDropsCacheBelowEdit() {
        var rec = new Recorder(clock, 0);
        var view = service(rec, opts()).open(SYNTAX, 100, lines(), 10);
        view.lines(0, 19);
        rec.seen.clear();
        view.invalidateFrom(10);
        view.lines(0, 19);
        assertEquals(10, rec.seen.size());
        assertEquals("line10", rec.seen.getFirst());
        assertEquals(1, rec.resets, "the replay continues from the kept lines");
    }

    @Test
    void twoViewsOfOneSyntaxReplayWhenInterleaved() {
        var rec = new Recorder(clock, 0);
        var svc = service(rec, opts());
        var a = svc.open(SYNTAX, 100, lines(), 10);
        var b = svc.open(SYNTAX, 100, lines(), 10);
        a.lines(0, 4);
        b.lines(0, 4);
        a.lines(5, 9);
        assertEquals(3, rec.resets);
    }

    @Test
    void longLineIsCutAndRestIsPlain() {
        var rec = new Recorder(clock, 0);
        var options = opts().withMaxLineChars(5);
        var view = service(rec, options).open(SYNTAX, 1, i -> "0123456789", 10);
        var out = view.lines(0, 0);
        assertEquals("01234", rec.seen.getFirst());
        assertEquals("0123456789", out.getFirst().toString());
        assertEquals(AttributedStyle.DEFAULT, out.getFirst().styleAt(7));
        assertFalse(out.getFirst().styleAt(1).equals(AttributedStyle.DEFAULT));
    }

    @Test
    void twoSlowPassesInARowTurnTheSyntaxOff() {
        var rec = new Recorder(clock, 300_000_000L);
        var svc = service(rec, opts());
        var view = svc.open(SYNTAX, 100, lines(), 10);
        var out = view.lines(0, 9);
        assertTrue(svc.isDisabled(SYNTAX));
        assertEquals(2, rec.seen.size(), "highlighting stops after the second slow pass");
        assertEquals(10, out.size());
        assertEquals(AttributedStyle.DEFAULT, out.getFirst().styleAt(0));
        assertEquals(1, view.takeNotices().size());
        assertTrue(view.takeNotices().isEmpty(), "the notice shows once");
        assertFalse(view.isColored());
        // A new document with the same syntax stays plain for the rest of the run.
        var other = svc.open(SYNTAX, 10, lines(), 10);
        other.lines(0, 3);
        assertEquals(2, rec.seen.size());
    }

    @Test
    void oneSlowPassFollowedByFastPassKeepsColors() {
        var slowThenFast = new LineHighlighter() {
            int n;

            @Override
            public AttributedString highlight(String line) {
                clock.addAndGet(n++ % 2 == 0 ? 300_000_000L : 0);
                return new AttributedString(line);
            }

            @Override
            public void reset() {
            }
        };
        var svc = new HighlightService(s -> slowThenFast, opts(), clock::get);
        var view = svc.open(SYNTAX, 20, lines(), 10);
        view.lines(0, 9);
        assertFalse(svc.isDisabled(SYNTAX));
    }

    @Test
    void fileAboveMaxBytesHasNoColorsAndNotice() {
        var rec = new Recorder(clock, 0);
        var svc = service(rec, opts());
        var view = svc.open(SYNTAX, 3, lines(), HighlightOptions.DEFAULT_MAX_BYTES + 1);
        var out = view.lines(0, 2);
        assertTrue(rec.seen.isEmpty());
        assertEquals("line2", out.getLast().toString());
        assertEquals(1, view.takeNotices().size());
        var ok = svc.open(SYNTAX, 3, lines(), HighlightOptions.DEFAULT_MAX_BYTES);
        ok.lines(0, 2);
        assertEquals(3, rec.seen.size());
    }

    @Test
    void configurableMaxBytes() {
        var rec = new Recorder(clock, 0);
        var view = service(rec, opts().withMaxBytes(100)).open(SYNTAX, 3, lines(), 101);
        view.lines(0, 2);
        assertTrue(rec.seen.isEmpty());
    }

    @Test
    void noColorAndDisabledAndNoSyntaxGivePlainText() {
        var rec = new Recorder(clock, 0);
        var noColor = service(rec, opts().withNoColor(true)).open(SYNTAX, 3, lines(), 10);
        assertEquals("line1", noColor.lines(1, 1).getFirst().toString());
        var none = service(rec, opts()).open(null, 3, lines(), 10);
        assertEquals("line2", none.lines(2, 2).getFirst().toString());
        var off = service(rec, new HighlightOptions(false, 10, 10, 10, 10, false)).open(SYNTAX, 3, lines(), 1);
        off.lines(0, 2);
        assertTrue(rec.seen.isEmpty());
    }

    @Test
    void throwingRuleGivesPlainLineAndKeepsGoing() {
        var thrower = new LineHighlighter() {
            @Override
            public AttributedString highlight(String line) {
                if (line.equals("line1")) {
                    throw new IllegalStateException("boom");
                }
                return new AttributedStringBuilder().style(AttributedStyle.DEFAULT.bold()).append(line)
                        .toAttributedString();
            }

            @Override
            public void reset() {
            }
        };
        var view = new HighlightService(s -> thrower, opts(), clock::get).open(SYNTAX, 3, lines(), 10);
        var out = view.lines(0, 2);
        assertEquals(AttributedStyle.DEFAULT, out.get(1).styleAt(0));
        assertFalse(out.get(2).styleAt(0).equals(AttributedStyle.DEFAULT));
    }

    @Test
    void factoryFailureTurnsSyntaxOffWithNotice() {
        var svc = new HighlightService(s -> {
            throw new java.io.IOException("bad file");
        }, opts(), clock::get);
        var view = svc.open(SYNTAX, 3, lines(), 10);
        assertEquals("line0", view.lines(0, 0).getFirst().toString());
        assertEquals(1, view.takeNotices().size());
        assertTrue(svc.isDisabled(SYNTAX));
    }

    @Test
    void realJLineEngineKeepsMultiLineRegionStateAcrossReplay() throws Exception {
        var dir = java.nio.file.Files.createTempDirectory("hs");
        var catalog = SyntaxCatalog.load(new SyntaxSettings(null, null, List.of(), List.of(), true,
                dir.resolve("cache"), "r"));
        var java = catalog.byName("java").orElseThrow();
        var text = List.of("int a;", "/* start", "inside", "end */ int b;", "int c;");
        var svc = new HighlightService(opts().withReplayLines(100));
        var view = svc.open(java, text.size(), text::get, 50);
        var all = view.lines(0, 4);
        var inside = all.get(2);
        var plainStyle = AttributedStyle.DEFAULT;
        assertFalse(inside.styleAt(0).equals(plainStyle), "a comment line has a style");
        // A jump into the middle replays from the top because the replay limit covers it.
        var again = svc.open(java, text.size(), text::get, 50).lines(2, 2);
        assertEquals(inside, again.getFirst());
    }
}
