package org.github.lscoughlin.jeorje.ui.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import org.github.lscoughlin.jeorje.ui.syntax.HighlighterFactory;
import org.github.lscoughlin.jeorje.ui.syntax.LineHighlighter;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxInfo;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shared state of the chat highlighting: the settings, the factory, and the safety rules.
 *
 * <p>A line longer than {@code maxLineChars} goes to the highlighter cut at that length. A syntax that is slow
 * two times in a row is turned off for the rest of the run. A syntax that throws gives plain lines.
 */
public final class HighlightContext {

    private static final Logger log = LoggerFactory.getLogger(HighlightContext.class);

    private final ChatHighlightConfig config;
    private final LanguageResolver resolver;
    private final HighlighterFactory factory;
    private final LongSupplier nanoClock;
    private final Map<String, Integer> slowStreak = new HashMap<>();
    private final Set<String> disabled = new HashSet<>();
    private final Set<String> warned = new HashSet<>();
    private final List<String> notices = new ArrayList<>();

    public HighlightContext(ChatHighlightConfig config, LanguageResolver resolver, HighlighterFactory factory) {
        this(config, resolver, factory, System::nanoTime);
    }

    public HighlightContext(ChatHighlightConfig config, LanguageResolver resolver, HighlighterFactory factory,
                            LongSupplier nanoClock) {
        this.config = config;
        this.resolver = resolver;
        this.factory = factory;
        this.nanoClock = nanoClock;
    }

    public ChatHighlightConfig config() {
        return config;
    }

    public LanguageResolver resolver() {
        return resolver;
    }

    /** True when the service turned the syntax off after slow passes. */
    public synchronized boolean isDisabled(SyntaxInfo syntax) {
        return disabled.contains(syntax.name());
    }

    /** Returns the notices since the last call. */
    public synchronized List<String> takeNotices() {
        var copy = List.copyOf(notices);
        notices.clear();
        return copy;
    }

    /** Opens a highlighter for the syntax, or returns null when the text must stay plain. */
    LineHighlighter open(SyntaxInfo syntax) {
        if (syntax == null || !config.codeColors() || isDisabled(syntax)) {
            return null;
        }
        try {
            return factory.create(syntax);
        } catch (Exception e) {
            warnOnce("open:" + syntax.name(), "Cannot load the syntax " + syntax.name() + ": " + e);
            return null;
        }
    }

    /** Highlights one line. Returns the plain line when the highlighter is null, off, or failed. */
    AttributedString highlight(SyntaxInfo syntax, LineHighlighter highlighter, String rawLine) {
        var line = display(rawLine);
        if (highlighter == null || syntax == null || isDisabled(syntax)) {
            return new AttributedString(line);
        }
        var cut = Math.min(line.length(), Math.max(1, config.maxLineChars()));
        var head = line.substring(0, cut);
        AttributedString styled;
        var start = nanoClock.getAsLong();
        try {
            styled = highlighter.highlight(head);
        } catch (RuntimeException e) {
            warnOnce("run:" + syntax.name(), "A rule of the syntax " + syntax.name() + " failed: " + e);
            return new AttributedString(line);
        }
        trackTime(syntax, (nanoClock.getAsLong() - start) / 1_000_000);
        if (styled == null || !styled.toString().equals(head)) {
            return new AttributedString(line);
        }
        if (cut == line.length()) {
            return styled;
        }
        var sb = new AttributedStringBuilder();
        sb.append(styled);
        sb.append(line.substring(cut));
        return sb.toAttributedString();
    }

    /** The text for display: tabs become spaces. */
    static String display(String line) {
        return line.replace("\t", "    ").replace("\r", "");
    }

    private synchronized void trackTime(SyntaxInfo syntax, long ms) {
        var key = syntax.name();
        if (ms > config.budgetMs()) {
            var streak = slowStreak.merge(key, 1, Integer::sum);
            if (streak >= 2 && disabled.add(key)) {
                notices.add("Colors are off for " + key + " in this run. The highlighter is too slow.");
                log.atWarn().log(() -> "Chat highlighting turned off for " + key + " after slow passes");
            }
        } else {
            slowStreak.put(key, 0);
        }
    }

    private synchronized void warnOnce(String key, String message) {
        if (warned.add(key)) {
            log.atWarn().log(() -> message);
        }
    }
}
