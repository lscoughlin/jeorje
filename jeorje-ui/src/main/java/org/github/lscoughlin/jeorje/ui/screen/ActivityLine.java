package org.github.lscoughlin.jeorje.ui.screen;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * The activity row above the input line. It shows a spinner, a word, the elapsed time, and the token counts of the
 * running model calls of one agent. The row is
 * {@code <frame> <Word>... (<elapsed> | ↑ <up> tokens | ↓ <down> tokens)}. All methods are thread safe.
 */
public final class ActivityLine {
    /** The frames of the spinner in UTF mode. */
    public static final String FRAMES = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";
    /** The frames of the spinner in no-color mode or non-UTF mode. */
    public static final String ASCII_FRAMES = "|/-\\";
    /** The words that the row shows, in order. */
    public static final List<String> WORDS = List.of("Thinking", "Pondering", "Musing", "Reasoning", "Mulling",
            "Considering", "Working", "Cooking", "Weighing", "Untangling");
    /** The time for which the row keeps one word. */
    public static final Duration WORD_TIME = Duration.ofSeconds(4);

    private final Clock clock;
    private final IntSupplier firstWord;
    private final boolean plain;

    private boolean active;
    private Instant started = Instant.EPOCH;
    private int wordStart;
    private int up;
    private int down;
    private int frame;

    /**
     * @param clock the clock for the elapsed time
     * @param firstWord gives the index in {@link #WORDS} of the first word of each new model call
     * @param plain true for no-color mode or non-UTF mode: the spinner uses ASCII and the row has no color
     */
    public ActivityLine(Clock clock, IntSupplier firstWord, boolean plain) {
        this.clock = clock;
        this.firstWord = firstWord;
        this.plain = plain;
    }

    /** A row that starts each model call at the next word of the list. */
    public static ActivityLine create(Clock clock, boolean plain) {
        var counter = new AtomicInteger();
        return new ActivityLine(clock, counter::getAndIncrement, plain);
    }

    /** Sets the state. A change from inactive to active starts the timer and picks the first word. */
    public synchronized void update(boolean active, int tokensUp, int tokensDown) {
        if (active && !this.active) {
            started = clock.instant();
            wordStart = Math.floorMod(firstWord.getAsInt(), WORDS.size());
            frame = 0;
        }
        this.active = active;
        this.up = tokensUp;
        this.down = tokensDown;
    }

    public synchronized boolean active() { return active; }

    /** Moves the spinner to the next frame. */
    public synchronized void tick() { frame++; }

    /** The styled row cut to the width, or null when no model call runs. */
    public synchronized AttributedString render(int width, Instant now) {
        if (!active) return null;
        var elapsed = Duration.between(started, now);
        if (elapsed.isNegative()) elapsed = Duration.ZERO;
        var frames = plain ? ASCII_FRAMES : FRAMES;
        var glyph = String.valueOf(frames.charAt(frame % frames.length()));
        var word = WORDS.get((wordStart + (int) (elapsed.toMillis() / WORD_TIME.toMillis())) % WORDS.size());
        var rest = " " + word + "... (" + elapsed(elapsed) + " | ↑ " + count(up) + " tokens | ↓ " + count(down)
                + " tokens)";
        var w = Math.max(1, width);
        var text = glyph + rest;
        var builder = new AttributedStringBuilder();
        if (text.length() > w) {
            text = text.substring(0, Math.max(0, w - 1)) + "…";
            builder.style(plain ? AttributedStyle.DEFAULT.faint() : AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN));
            builder.append(text.substring(0, Math.min(1, text.length())));
            builder.style(AttributedStyle.DEFAULT.faint());
            if (text.length() > 1) builder.append(text.substring(1));
            return builder.toAttributedString();
        }
        builder.style(plain ? AttributedStyle.DEFAULT.faint() : AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN));
        builder.append(glyph);
        builder.style(AttributedStyle.DEFAULT.faint());
        builder.append(rest);
        return builder.toAttributedString();
    }

    /** {@code Ns} below 60 seconds, then {@code Nm Ns}. */
    public static String elapsed(Duration elapsed) {
        var seconds = Math.max(0, elapsed.getSeconds());
        return seconds < 60 ? seconds + "s" : seconds / 60 + "m " + seconds % 60 + "s";
    }

    /** The number below 1000, then one decimal and {@code k}, for example {@code 1.2k}. */
    public static String count(int tokens) {
        return tokens < 1000 ? Integer.toString(Math.max(0, tokens))
                : String.format(Locale.ROOT, "%.1fk", tokens / 1000.0);
    }
}
