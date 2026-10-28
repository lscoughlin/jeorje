package org.github.lscoughlin.jeorje.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

class InputLineStyleTest {
    private static final long INVERSE_FLAG = AttributedStyle.INVERSE.getStyle();

    @Test
    void colorModeUsesABackgroundThatDiffersFromTheDefault() {
        var style = InputLineStyle.inputStyle(true);
        assertNotEquals(AttributedStyle.DEFAULT.background(-1).getStyle(), style.getStyle());
        assertEquals(0L, style.getStyle() & INVERSE_FLAG);
    }

    @Test
    void noColorModeUsesInverseVideo() {
        var style = InputLineStyle.inputStyle(false);
        assertEquals(INVERSE_FLAG, style.getStyle() & INVERSE_FLAG);
        assertEquals(AttributedStyle.DEFAULT.inverse().getStyle(), style.getStyle());
    }

    @Test
    void theRowHasInverseVideoInNoColorMode() {
        var row = InputLineStyle.row("> hello", 20, false);
        assertEquals(INVERSE_FLAG, row.styleAt(0).getStyle() & INVERSE_FLAG);
        assertEquals(INVERSE_FLAG, row.styleAt(19).getStyle() & INVERSE_FLAG);
    }

    @Test
    void theRowHasANonDefaultBackgroundInColorMode() {
        var row = InputLineStyle.row("> hello", 20, true);
        assertNotEquals(AttributedStyle.DEFAULT.background(-1).getStyle(), row.styleAt(0).getStyle());
        assertEquals(0L, row.styleAt(0).getStyle() & INVERSE_FLAG);
    }

    @Test
    void theRowFillsExactlyTheRequestedWidth() {
        var row = InputLineStyle.row("> hi", 30, true);
        assertEquals(30, row.columnLength());
        assertTrue(row.toString().startsWith("> hi"), row.toString());
        assertTrue(row.toString().equals("> hi" + " ".repeat(26)), row.toString());
        assertEquals(0, InputLineStyle.row("", 0, false).columnLength());
    }

    @Test
    void textLongerThanTheWidthStaysAsItIs() {
        var row = InputLineStyle.row("> a very long input text", 8, true);
        assertEquals("> a very long input text", row.toString());
    }
}
