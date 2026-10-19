package org.github.lscoughlin.jeorje.ui.chat;

import java.util.Locale;

/**
 * How much of the work of the agent the chat view shows.
 *
 * @see #QUIET hides the tool calls and their results
 * @see #NORMAL shows one line for each tool call, and a collapsed result
 * @see #VERBOSE shows the full result of each tool call
 */
public enum Verbosity {
    QUIET,
    NORMAL,
    VERBOSE;

    /** The value of {@code ui.chat.verbosity} for this level. */
    public String key() { return name().toLowerCase(Locale.ROOT); }

    /**
     * Reads a value of {@code ui.chat.verbosity}.
     *
     * @return the level, or null when the text is not a level
     */
    public static Verbosity parse(String text) {
        if (text == null) return null;
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "quiet" -> QUIET;
            case "normal" -> NORMAL;
            case "verbose" -> VERBOSE;
            default -> null;
        };
    }
}
