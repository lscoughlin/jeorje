package org.github.lscoughlin.jeorje.ui.editor;

import org.jline.utils.AttributedStyle;

/** The styles of the editor. The dark palette is light text on a dark blue ground. The mono palette has no colors. */
record Palette(
        AttributedStyle text,
        AttributedStyle selection,
        AttributedStyle menuBar,
        AttributedStyle menuHot,
        AttributedStyle menuSelected,
        AttributedStyle menuItem,
        AttributedStyle menuDisabled,
        AttributedStyle frame,
        AttributedStyle title,
        AttributedStyle status,
        AttributedStyle statusNotice,
        AttributedStyle dialog,
        AttributedStyle field,
        AttributedStyle button,
        AttributedStyle buttonFocus,
        AttributedStyle lineNumber,
        AttributedStyle whitespace,
        AttributedStyle diffAdd,
        AttributedStyle diffRemove,
        AttributedStyle diffChange,
        AttributedStyle diffWord,
        AttributedStyle scrollBar,
        boolean colors) {

    private static final int BLACK = 0;
    private static final int RED = 1;
    private static final int GREEN = 2;
    private static final int YELLOW = 3;
    private static final int BLUE = 4;
    private static final int CYAN = 6;
    private static final int WHITE = 7;
    private static final int BRIGHT_WHITE = 15;
    private static final int GRAY = 8;

    private static AttributedStyle style(int fg, int bg) {
        return AttributedStyle.DEFAULT.foreground(fg).background(bg);
    }

    static Palette dark() {
        return new Palette(
                style(WHITE, BLUE),
                style(BLUE, WHITE),
                style(BLACK, WHITE),
                style(RED, WHITE).bold(),
                style(WHITE, BLACK),
                style(BLACK, WHITE),
                style(GRAY, WHITE),
                style(BRIGHT_WHITE, BLUE),
                style(BLUE, WHITE),
                style(BLACK, CYAN),
                style(BLACK, YELLOW),
                style(BLACK, WHITE),
                style(WHITE, BLACK),
                style(BLACK, GRAY),
                style(WHITE, BLACK).bold(),
                style(GRAY, BLUE),
                style(GRAY, BLUE),
                style(BRIGHT_WHITE, GREEN),
                style(BRIGHT_WHITE, RED),
                style(BLACK, YELLOW),
                style(BLACK, WHITE).bold(),
                style(CYAN, BLUE),
                true);
    }

    static Palette mono() {
        var plain = AttributedStyle.DEFAULT;
        var inverse = AttributedStyle.DEFAULT.inverse();
        return new Palette(
                plain,
                inverse,
                inverse,
                inverse.underline(),
                plain.bold(),
                inverse,
                inverse.faint(),
                plain,
                plain.bold(),
                inverse,
                inverse.bold(),
                plain,
                plain.underline(),
                plain,
                inverse,
                plain.faint(),
                plain.faint(),
                plain,
                plain,
                plain,
                plain.bold().underline(),
                plain,
                false);
    }

    /** Puts the ground of the text on a style from the syntax highlighter. */
    AttributedStyle onText(AttributedStyle s) {
        if (!colors) {
            return s;
        }
        return s.background(BLUE);
    }
}
