package org.github.lscoughlin.jeorje.tools.registry;

import org.github.lscoughlin.jeorje.core.model.ToolResultData;

/** Caps the size of a tool result and refuses binary content. */
public final class OutputLimiter {
    /** The default cap in characters. */
    public static final int DEFAULT_MAX_CHARS = 30_000;

    private final int maxChars;

    public OutputLimiter(int maxChars) {
        if (maxChars < 1) throw new IllegalArgumentException("maxChars must be positive");
        this.maxChars = maxChars;
    }

    public int maxChars() {
        return maxChars;
    }

    /** Applies the binary check and then the cap. */
    public ToolResultData apply(ToolResultData result) {
        var text = result.output();
        if (text.indexOf('\0') >= 0) {
            return ToolResultData.error("The tool returned binary content. Jeorje does not show binary content.");
        }
        if (text.length() <= maxChars) return result;
        var cut = maxChars;
        if (Character.isHighSurrogate(text.charAt(cut - 1))) cut--;
        var note = "\n[Output cut: showing " + cut + " of " + text.length() + " characters. "
                + "To get more, use the offset and limit parameters of the tool, narrow the request, "
                + "or use recall for the stored chunk.]";
        return result.withOutput(text.substring(0, cut) + note);
    }
}
