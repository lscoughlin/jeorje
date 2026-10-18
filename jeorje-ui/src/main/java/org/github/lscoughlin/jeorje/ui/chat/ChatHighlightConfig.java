package org.github.lscoughlin.jeorje.ui.chat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings for the highlighting in the chat view. The keys are under {@code ui.chat.highlight}.
 *
 * @param enabled              false turns all colors off
 * @param maxLines             lines of a block that get colors
 * @param diff                 diff rendering for edit results and diff blocks
 * @param read                 colors for read and grep results
 * @param fences               colors for fenced code blocks
 * @param aliases              more tags, for example {@code kts -> kotlin}
 * @param editorSyntaxEnabled  the value of {@code editor.syntax.enabled}; false removes code colors, not diff tints
 * @param noColor              true when {@code NO_COLOR} is set
 * @param fewColors            true when the terminal has fewer than 256 colors
 * @param maxLineChars         longest line that goes to the highlighter
 * @param budgetMs             time for one line; two slow lines in a row turn the syntax off
 */
public record ChatHighlightConfig(boolean enabled, int maxLines, boolean diff, boolean read, boolean fences,
                                  Map<String, String> aliases, boolean editorSyntaxEnabled, boolean noColor,
                                  boolean fewColors, int maxLineChars, long budgetMs) {

    public ChatHighlightConfig {
        aliases = Map.copyOf(aliases);
    }

    public static ChatHighlightConfig defaults() {
        return new ChatHighlightConfig(true, 2000, true, true, true, Map.of(), true,
                System.getenv("NO_COLOR") != null, false, 2000, 200);
    }

    /** Reads the keys of {@code ui.chat.highlight}. A missing key keeps the default. */
    public static ChatHighlightConfig fromMap(Map<?, ?> map) {
        var base = defaults();
        var aliases = new LinkedHashMap<String, String>();
        if (map.get("aliases") instanceof Map<?, ?> given) {
            given.forEach((k, v) -> aliases.put(String.valueOf(k).toLowerCase(), String.valueOf(v)));
        }
        return new ChatHighlightConfig(flag(map, "enabled", base.enabled), number(map, "maxLines", base.maxLines),
                flag(map, "diff", base.diff), flag(map, "read", base.read), flag(map, "fences", base.fences),
                aliases, base.editorSyntaxEnabled, base.noColor, base.fewColors, base.maxLineChars, base.budgetMs);
    }

    private static boolean flag(Map<?, ?> map, String key, boolean fallback) {
        return map.get(key) instanceof Boolean b ? b : fallback;
    }

    private static int number(Map<?, ?> map, String key, int fallback) {
        return map.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    /** True when the syntax colors of code are on. */
    public boolean codeColors() {
        return enabled && editorSyntaxEnabled && !noColor;
    }

    /** True when diff rendering is on. */
    public boolean diffRendering() {
        return enabled && diff;
    }

    public ChatHighlightConfig withEnabled(boolean v) {
        return new ChatHighlightConfig(v, maxLines, diff, read, fences, aliases, editorSyntaxEnabled, noColor,
                fewColors, maxLineChars, budgetMs);
    }

    public ChatHighlightConfig withMaxLines(int v) {
        return new ChatHighlightConfig(enabled, v, diff, read, fences, aliases, editorSyntaxEnabled, noColor,
                fewColors, maxLineChars, budgetMs);
    }

    public ChatHighlightConfig withDiff(boolean v) {
        return new ChatHighlightConfig(enabled, maxLines, v, read, fences, aliases, editorSyntaxEnabled, noColor,
                fewColors, maxLineChars, budgetMs);
    }

    public ChatHighlightConfig withRead(boolean v) {
        return new ChatHighlightConfig(enabled, maxLines, diff, v, fences, aliases, editorSyntaxEnabled, noColor,
                fewColors, maxLineChars, budgetMs);
    }

    public ChatHighlightConfig withFences(boolean v) {
        return new ChatHighlightConfig(enabled, maxLines, diff, read, v, aliases, editorSyntaxEnabled, noColor,
                fewColors, maxLineChars, budgetMs);
    }

    public ChatHighlightConfig withAliases(Map<String, String> v) {
        return new ChatHighlightConfig(enabled, maxLines, diff, read, fences, v, editorSyntaxEnabled, noColor,
                fewColors, maxLineChars, budgetMs);
    }

    public ChatHighlightConfig withEditorSyntaxEnabled(boolean v) {
        return new ChatHighlightConfig(enabled, maxLines, diff, read, fences, aliases, v, noColor, fewColors,
                maxLineChars, budgetMs);
    }

    public ChatHighlightConfig withNoColor(boolean v) {
        return new ChatHighlightConfig(enabled, maxLines, diff, read, fences, aliases, editorSyntaxEnabled, v,
                fewColors, maxLineChars, budgetMs);
    }

    public ChatHighlightConfig withFewColors(boolean v) {
        return new ChatHighlightConfig(enabled, maxLines, diff, read, fences, aliases, editorSyntaxEnabled,
                noColor, v, maxLineChars, budgetMs);
    }

    public ChatHighlightConfig withMaxLineChars(int v) {
        return new ChatHighlightConfig(enabled, maxLines, diff, read, fences, aliases, editorSyntaxEnabled,
                noColor, fewColors, v, budgetMs);
    }

    public ChatHighlightConfig withBudgetMs(long v) {
        return new ChatHighlightConfig(enabled, maxLines, diff, read, fences, aliases, editorSyntaxEnabled,
                noColor, fewColors, maxLineChars, v);
    }
}
