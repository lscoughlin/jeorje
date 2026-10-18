package org.github.lscoughlin.jeorje.ui.keys;

import java.util.EnumSet;
import java.util.Locale;
import org.jline.terminal.Terminal;

/** The value of {@code ui.keyboardProtocol}: whether Jeorje turns on the Kitty keyboard protocol. */
public enum KeyboardProtocol {
    AUTO,
    ON,
    OFF;

    /** Parses {@code auto}, {@code on}, or {@code off} (not case sensitive). Null gives {@code AUTO}. */
    public static KeyboardProtocol parse(String text) {
        if (text == null) {
            return AUTO;
        }
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> AUTO;
            case "on", "true" -> ON;
            case "off", "false" -> OFF;
            default -> throw new IllegalArgumentException(
                    "Unknown value '" + text + "' for ui.keyboardProtocol. Use auto, on, or off.");
        };
    }

    /**
     * Turns on the Kitty keyboard protocol when the mode and the terminal allow it.
     *
     * @return true when the protocol is on. Call {@link #disable} at exit in that case.
     */
    public boolean enable(Terminal terminal) {
        return switch (this) {
            case OFF -> false;
            case AUTO -> terminal.hasKittyKeyboardSupport()
                    && terminal.setKittyKeyboardMode(EnumSet.of(Terminal.KittyKeyboardMode.Disambiguate));
            case ON -> terminal.setKittyKeyboardMode(EnumSet.of(Terminal.KittyKeyboardMode.Disambiguate));
        };
    }

    /** Turns the Kitty keyboard protocol off. */
    public static void disable(Terminal terminal) {
        terminal.resetKittyKeyboardMode();
    }
}
