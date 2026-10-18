package org.github.lscoughlin.jeorje.ui.editor.markdown;

import java.util.Map;

/**
 * Settings of the Markdown injection layer.
 *
 * @param enabled        the value of {@code editor.syntax.injections}
 * @param replayLines    lines to replay above the first visible line in a long block
 * @param maxBlockLines  a block with more lines is highlighted from the visible part only
 * @param maxLineChars   longest line that goes to a highlighter
 * @param noColor        true when the {@code NO_COLOR} variable is set
 */
public record InjectionOptions(boolean enabled, int replayLines, int maxBlockLines, int maxLineChars,
                               boolean noColor) {

    /** The config key that turns the layer on or off. */
    public static final String KEY = "editor.syntax.injections";

    public static InjectionOptions defaults() {
        return new InjectionOptions(true, 2000, 5000, 2000, System.getenv("NO_COLOR") != null);
    }

    /** Reads the key from a flat config map. A missing key means on. */
    public static InjectionOptions fromConfig(Map<String, ?> config) {
        var value = config.get(KEY);
        var on = switch (value) {
            case null -> true;
            case Boolean b -> b;
            default -> !"false".equalsIgnoreCase(value.toString().strip());
        };
        return defaults().withEnabled(on);
    }

    public InjectionOptions withEnabled(boolean value) {
        return new InjectionOptions(value, replayLines, maxBlockLines, maxLineChars, noColor);
    }

    public InjectionOptions withReplayLines(int value) {
        return new InjectionOptions(enabled, value, maxBlockLines, maxLineChars, noColor);
    }

    public InjectionOptions withMaxBlockLines(int value) {
        return new InjectionOptions(enabled, replayLines, value, maxLineChars, noColor);
    }

    public InjectionOptions withNoColor(boolean value) {
        return new InjectionOptions(enabled, replayLines, maxBlockLines, maxLineChars, value);
    }
}
