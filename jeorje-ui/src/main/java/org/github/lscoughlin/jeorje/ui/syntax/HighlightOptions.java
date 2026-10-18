package org.github.lscoughlin.jeorje.ui.syntax;

/**
 * Limits for {@link HighlightService}.
 *
 * @param enabled       false turns all colors off
 * @param replayLines   lines to replay above the first visible line
 * @param maxLineChars  longest line that goes to the highlighter
 * @param budgetMs      time for one line pass; two slow passes in a row turn the syntax off
 * @param maxBytes      largest document that gets colors
 * @param noColor       true when the {@code NO_COLOR} variable is set
 */
public record HighlightOptions(boolean enabled, int replayLines, int maxLineChars, long budgetMs, long maxBytes,
                                boolean noColor) {

    public static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;

    public static HighlightOptions defaults() {
        return new HighlightOptions(true, 2000, 2000, 200, DEFAULT_MAX_BYTES, System.getenv("NO_COLOR") != null);
    }

    public HighlightOptions withReplayLines(int value) {
        return new HighlightOptions(enabled, value, maxLineChars, budgetMs, maxBytes, noColor);
    }

    public HighlightOptions withMaxLineChars(int value) {
        return new HighlightOptions(enabled, replayLines, value, budgetMs, maxBytes, noColor);
    }

    public HighlightOptions withBudgetMs(long value) {
        return new HighlightOptions(enabled, replayLines, maxLineChars, value, maxBytes, noColor);
    }

    public HighlightOptions withMaxBytes(long value) {
        return new HighlightOptions(enabled, replayLines, maxLineChars, budgetMs, value, noColor);
    }

    public HighlightOptions withNoColor(boolean value) {
        return new HighlightOptions(enabled, replayLines, maxLineChars, budgetMs, maxBytes, value);
    }
}
