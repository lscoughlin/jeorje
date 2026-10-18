package org.github.lscoughlin.jeorje.ui.syntax;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds one highlighter for each syntax and gives styled lines for documents.
 *
 * <p>The JLine engine cannot save its state. A {@link View} therefore replays lines after a jump.
 */
public final class HighlightService {

    private static final Logger log = LoggerFactory.getLogger(HighlightService.class);

    private final HighlighterFactory factory;
    private final HighlightOptions options;
    private final LongSupplier nanoClock;

    private final Map<String, LineHighlighter> highlighters = new HashMap<>();
    private final Map<String, View> owners = new HashMap<>();
    private final Map<String, Integer> slowStreak = new HashMap<>();
    private final Set<String> disabled = new HashSet<>();
    private final Set<String> warnedFailures = new HashSet<>();

    public HighlightService(HighlightOptions options) {
        this(HighlighterFactory.jline(), options, System::nanoTime);
    }

    public HighlightService(HighlighterFactory factory, HighlightOptions options, LongSupplier nanoClock) {
        this.factory = factory;
        this.options = options;
        this.nanoClock = nanoClock;
    }

    /**
     * Opens a document.
     *
     * @param syntax    the syntax, or null for plain text
     * @param lineCount the number of lines
     * @param lineAt    gives the text of a line
     * @param bytes     the size of the document in bytes
     */
    public View open(SyntaxInfo syntax, int lineCount, IntFunction<String> lineAt, long bytes) {
        return new View(syntax, lineCount, lineAt, bytes);
    }

    /** True when the service turned the syntax off after slow passes. */
    public synchronized boolean isDisabled(SyntaxInfo syntax) {
        return disabled.contains(key(syntax));
    }

    private static String key(SyntaxInfo syntax) {
        return syntax.name();
    }

    /** A document with a cache of styled lines. */
    public final class View {
        private final SyntaxInfo syntax;
        private final IntFunction<String> lineAt;
        private int lineCount;
        private final String plainReason;
        private final Map<Integer, AttributedString> cache = new HashMap<>();
        private int replayStart = 0;
        private int cachedEnd = 0; // lines [replayStart, cachedEnd) are in the cache
        private final List<String> notices = new ArrayList<>();
        private boolean replays;

        private View(SyntaxInfo syntax, int lineCount, IntFunction<String> lineAt, long bytes) {
            this.syntax = syntax;
            this.lineCount = lineCount;
            this.lineAt = lineAt;
            String reason = null;
            if (syntax == null || !options.enabled() || options.noColor()) {
                reason = "";
            } else if (bytes > options.maxBytes()) {
                reason = "Syntax colors are off: the file is larger than " + options.maxBytes() + " bytes.";
                notices.add(reason);
            }
            this.plainReason = reason;
        }

        /** True when this document gets colors now. */
        public boolean isColored() {
            return plainReason == null && !isDisabled(syntax);
        }

        /** Returns and clears the notices for the user. */
        public synchronized List<String> takeNotices() {
            var copy = List.copyOf(notices);
            notices.clear();
            return copy;
        }

        /** The number of lines of the last replay start; for tests and status. */
        public synchronized int replayStart() {
            return replayStart;
        }

        /** True when the last request started a replay. */
        public synchronized boolean lastRequestReplayed() {
            return replays;
        }

        public synchronized void setLineCount(int count) {
            this.lineCount = count;
        }

        /** Drops the cache from the line down. Call it after an edit. */
        public synchronized void invalidateFrom(int line) {
            cache.keySet().removeIf(k -> k >= line);
            cachedEnd = Math.min(cachedEnd, Math.max(line, replayStart));
        }

        /** Returns the styled lines {@code first..last} (inclusive), cut to the document. */
        public List<AttributedString> lines(int first, int last) {
            synchronized (HighlightService.this) {
                synchronized (this) {
                    return doLines(first, last);
                }
            }
        }

        private List<AttributedString> doLines(int first, int last) {
            replays = false;
            first = Math.max(0, first);
            last = Math.min(last, lineCount - 1);
            var out = new ArrayList<AttributedString>();
            if (last < first) {
                return out;
            }
            if (!isColored()) {
                for (var i = first; i <= last; i++) {
                    out.add(new AttributedString(lineAt.apply(i)));
                }
                return out;
            }
            var k = key(syntax);
            var highlighter = highlighter(k);
            if (highlighter == null) {
                return plain(first, last);
            }
            var continuous = owners.get(k) == this && first >= replayStart && first <= cachedEnd;
            if (!continuous) {
                replays = true;
                highlighter.reset();
                owners.put(k, this);
                cache.clear();
                replayStart = Math.max(0, first - options.replayLines());
                cachedEnd = replayStart;
            }
            while (cachedEnd <= last) {
                var line = lineAt.apply(cachedEnd);
                var styled = highlightLine(k, highlighter, line);
                if (styled == null) {
                    // The syntax went off during the pass.
                    return plain(first, last);
                }
                cache.put(cachedEnd, styled);
                cachedEnd++;
            }
            for (var i = first; i <= last; i++) {
                out.add(cache.get(i));
            }
            return out;
        }

        private List<AttributedString> plain(int first, int last) {
            var out = new ArrayList<AttributedString>();
            for (var i = first; i <= last; i++) {
                out.add(new AttributedString(lineAt.apply(i)));
            }
            return out;
        }

        private AttributedString highlightLine(String k, LineHighlighter highlighter, String line) {
            var max = options.maxLineChars();
            var head = line.length() > max ? line.substring(0, max) : line;
            var start = nanoClock.getAsLong();
            AttributedString styled;
            try {
                styled = highlighter.highlight(head);
            } catch (RuntimeException e) {
                if (warnedFailures.add(k)) {
                    log.atWarn().log(() -> "A rule of the syntax " + syntax.name() + " failed: " + e);
                }
                return new AttributedString(line);
            }
            var elapsedMs = (nanoClock.getAsLong() - start) / 1_000_000;
            if (elapsedMs > options.budgetMs()) {
                var streak = slowStreak.merge(k, 1, Integer::sum);
                if (streak >= 2) {
                    disabled.add(k);
                    var message = "Syntax colors for " + syntax.name() + " are off: highlighting is too slow.";
                    notices.add(message);
                    log.atWarn().log(() -> message);
                    return null;
                }
            } else {
                slowStreak.put(k, 0);
            }
            if (line.length() > max) {
                return new AttributedStringBuilder().append(styled).append(line.substring(max)).toAttributedString();
            }
            return styled;
        }

        private LineHighlighter highlighter(String k) {
            var existing = highlighters.get(k);
            if (existing != null) {
                return existing;
            }
            try {
                var created = factory.create(syntax);
                highlighters.put(k, created);
                return created;
            } catch (Exception | LinkageError e) {
                disabled.add(k);
                notices.add("Syntax colors for " + syntax.name() + " are off: the syntax file failed to load.");
                log.atWarn().log(() -> "Cannot load the syntax " + syntax.name() + " from " + syntax.file() + ": " + e);
                return null;
            }
        }
    }

    /** Convenience: the syntax name of a view, if any. */
    public static Optional<String> nameOf(SyntaxInfo syntax) {
        return Optional.ofNullable(syntax).map(SyntaxInfo::name);
    }
}
