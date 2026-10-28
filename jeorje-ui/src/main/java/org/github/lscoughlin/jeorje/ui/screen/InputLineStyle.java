package org.github.lscoughlin.jeorje.ui.screen;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

/**
 * The style of the chat input line. The input line has a slightly darker background than the chat, so the user can
 * see where to type. The style has no dependency on real terminal I/O.
 */
public final class InputLineStyle {
    private InputLineStyle() {}

    /**
     * The style of the input line.
     *
     * @param colors true for a dark-gray background, false for inverse video
     */
    public static AttributedStyle inputStyle(boolean colors) {
        return colors
                ? AttributedStyle.DEFAULT.background(AttributedStyle.BRIGHT | AttributedStyle.BLACK)
                : AttributedStyle.DEFAULT.inverse();
    }

    /**
     * One input row. The text keeps the prompt marker of the caller. Spaces fill the row to the given width, so the
     * background covers the full row. Text longer than the width stays as it is.
     *
     * @param text the text of the row, with the prompt marker of the first line
     * @param columns the visible width of the row
     * @param colors true for color mode, false for no-color mode
     */
    public static AttributedString row(String text, int columns, boolean colors) {
        var t = text == null ? "" : text;
        var width = Math.max(0, columns);
        var used = new AttributedString(t).columnLength();
        var padded = used >= width ? t : t + " ".repeat(width - used);
        return new AttributedString(padded, inputStyle(colors));
    }
}
