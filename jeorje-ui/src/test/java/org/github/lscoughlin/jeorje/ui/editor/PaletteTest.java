package org.github.lscoughlin.jeorje.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

/** The dark palette paints no background, and the mono palette has no colors. */
class PaletteTest {
    private static final int BLACK = 0;
    private static final int RED = 1;
    private static final int GREEN = 2;
    private static final int YELLOW = 3;
    private static final int BLUE = 4;
    private static final int CYAN = 6;
    private static final int WHITE = 7;
    private static final int BRIGHT_WHITE = 15;

    private static AttributedStyle style(int fg, int bg) {
        return AttributedStyle.DEFAULT.foreground(fg).background(bg);
    }

    @Test
    void theDarkPalettePaintsNoBackground() {
        var p = Palette.dark();
        var plain = AttributedStyle.DEFAULT;
        var inverse = AttributedStyle.DEFAULT.inverse();
        assertEquals(plain, p.text());
        assertEquals(inverse, p.selection());
        assertEquals(plain, p.menuBar());
        assertEquals(AttributedStyle.DEFAULT.foreground(RED).bold(), p.menuHot());
        assertEquals(inverse, p.menuSelected());
        assertEquals(plain, p.menuItem());
        assertEquals(AttributedStyle.DEFAULT.faint(), p.menuDisabled());
        assertEquals(plain, p.frame());
        assertEquals(style(BLUE, WHITE), p.title());
        assertEquals(inverse, p.status());
        assertEquals(inverse.bold(), p.statusNotice());
        assertEquals(plain, p.dialog());
        assertEquals(AttributedStyle.DEFAULT.underline(), p.field());
        assertEquals(plain, p.button());
        assertEquals(inverse.bold(), p.buttonFocus());
        assertEquals(AttributedStyle.DEFAULT.faint(), p.lineNumber());
        assertEquals(AttributedStyle.DEFAULT.faint(), p.whitespace());
        assertEquals(style(BRIGHT_WHITE, GREEN), p.diffAdd());
        assertEquals(style(BRIGHT_WHITE, RED), p.diffRemove());
        assertEquals(style(BLACK, YELLOW), p.diffChange());
        assertEquals(style(BLACK, WHITE).bold(), p.diffWord());
        assertEquals(AttributedStyle.DEFAULT.foreground(CYAN), p.scrollBar());
    }

    @Test
    void onTextKeepsTheTerminalGround() {
        var p = Palette.dark();
        var syntax = AttributedStyle.DEFAULT.foreground(RED);
        assertEquals(syntax.backgroundDefault(), p.onText(syntax));
        assertEquals(AttributedStyle.DEFAULT.backgroundDefault(), p.onText(AttributedStyle.DEFAULT));
    }

    @Test
    void theDarkPaletteHasColors() {
        assertTrue(Palette.dark().colors());
    }

    @Test
    void theMonoPaletteHasNoColors() {
        var p = Palette.mono();
        assertFalse(p.colors());
        assertEquals(AttributedStyle.DEFAULT, p.text());
        assertEquals(AttributedStyle.DEFAULT, p.onText(AttributedStyle.DEFAULT));
    }
}
