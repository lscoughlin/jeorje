package org.github.lscoughlin.jeorje.ui.mouse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.jline.terminal.Terminal;

/**
 * Copies text. It sends the text to the clipboard of the terminal with the OSC 52 escape sequence. If the
 * terminal does not support OSC 52, it keeps the text in an internal clipboard.
 */
public final class Clipboard {
    /** Where a copy went. */
    public enum Target {
        OSC52("Copied with OSC 52."),
        INTERNAL("Copied to the internal clipboard.");

        private final String status;

        Target(String status) { this.status = status; }

        public String status() { return status; }
    }

    private final Terminal terminal;
    private final boolean osc52;
    private String internal = "";

    public Clipboard(Terminal terminal, boolean osc52Supported) {
        this.terminal = terminal;
        this.osc52 = osc52Supported;
    }

    /** A guess from the environment. Terminals with a basic type do not get OSC 52. */
    public static boolean osc52Likely(Map<String, String> env) {
        var term = env.getOrDefault("TERM", "");
        return !(term.isEmpty() || term.equals("dumb") || term.equals("linux") || term.startsWith("screen"));
    }

    public synchronized Target copy(String text) {
        internal = text;
        if (!osc52) return Target.INTERNAL;
        var encoded = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        var out = terminal.writer();
        out.print("\u001b]52;c;" + encoded + "\u0007");
        out.flush();
        return Target.OSC52;
    }

    /** The text of the last copy. The editor and the input line paste this text. */
    public synchronized String internalText() { return internal; }
}
