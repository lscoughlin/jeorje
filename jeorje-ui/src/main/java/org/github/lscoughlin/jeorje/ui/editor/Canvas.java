package org.github.lscoughlin.jeorje.ui.editor;

import java.util.ArrayList;
import java.util.List;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/** A grid of styled cells. The editor draws a screen on a canvas. Each cell is one column. */
final class Canvas {
    private final int width;
    private final int height;
    private final char[][] chars;
    private final AttributedStyle[][] styles;

    Canvas(int width, int height, AttributedStyle fill) {
        this.width = width;
        this.height = height;
        chars = new char[height][width];
        styles = new AttributedStyle[height][width];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                chars[y][x] = ' ';
                styles[y][x] = fill;
            }
        }
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    void set(int x, int y, char c, AttributedStyle style) {
        if (x >= 0 && x < width && y >= 0 && y < height) {
            chars[y][x] = c;
            styles[y][x] = style;
        }
    }

    /** Writes text from a cell to the right. Text that does not fit is cut. */
    void put(int x, int y, String text, AttributedStyle style) {
        for (var i = 0; i < text.length(); i++) {
            set(x + i, y, text.charAt(i), style);
        }
    }

    /** Writes styled text from a cell to the right. */
    void put(int x, int y, AttributedString text) {
        for (var i = 0; i < text.length(); i++) {
            set(x + i, y, text.charAt(i), text.styleAt(i));
        }
    }

    void fill(int x, int y, int w, int h, char c, AttributedStyle style) {
        for (var row = y; row < y + h; row++) {
            for (var col = x; col < x + w; col++) {
                set(col, row, c, style);
            }
        }
    }

    /** Draws a frame with single lines. */
    void box(int x, int y, int w, int h, AttributedStyle style) {
        if (w < 2 || h < 2) {
            return;
        }
        set(x, y, '┌', style);
        set(x + w - 1, y, '┐', style);
        set(x, y + h - 1, '└', style);
        set(x + w - 1, y + h - 1, '┘', style);
        for (var col = x + 1; col < x + w - 1; col++) {
            set(col, y, '─', style);
            set(col, y + h - 1, '─', style);
        }
        for (var row = y + 1; row < y + h - 1; row++) {
            set(x, row, '│', style);
            set(x + w - 1, row, '│', style);
        }
    }

    /** The text of one row with no style. */
    String rowText(int y) {
        return new String(chars[y]);
    }

    /** The rows as styled lines. */
    List<AttributedString> toLines() {
        var out = new ArrayList<AttributedString>();
        for (var y = 0; y < height; y++) {
            var sb = new AttributedStringBuilder();
            var run = new StringBuilder();
            var current = styles[y][0];
            for (var x = 0; x < width; x++) {
                if (!styles[y][x].equals(current)) {
                    sb.style(current).append(run);
                    run.setLength(0);
                    current = styles[y][x];
                }
                run.append(chars[y][x]);
            }
            sb.style(current).append(run);
            out.add(sb.toAttributedString());
        }
        return out;
    }
}
