package org.github.lscoughlin.jeorje.ui.keys;

import java.util.stream.Collectors;

/** Text help for the effective key bindings. The slash command {@code /keys} and {@code jeorje config --keys} use it. */
public final class KeyHelp {

    private KeyHelp() {}

    /** One line per action: id, keys, source, and description. Groups follow the contexts. */
    public static String render(KeyBindings bindings) {
        return java.util.Arrays.stream(KeyScope.values())
                .map(scope -> render(bindings, scope))
                .collect(Collectors.joining("\n"));
    }

    /** The help for one scope ({@code ui.keys} or {@code editor.keys}). */
    public static String render(KeyBindings bindings, KeyScope scope) {
        var sb = new StringBuilder();
        sb.append(scope.configKey()).append('\n');
        for (var context : KeyContext.values()) {
            if (context.scope() != scope) {
                continue;
            }
            sb.append("  ").append(context.title()).append('\n');
            var list = bindings.bindings(context);
            var idWidth = list.stream().mapToInt(b -> b.action().id().length()).max().orElse(0);
            var keyWidth = list.stream().mapToInt(b -> b.display().length()).max().orElse(0);
            for (var b : list) {
                sb.append("    ")
                        .append(pad(b.action().id(), idWidth)).append("  ")
                        .append(pad(b.display(), keyWidth)).append("  ")
                        .append(pad("(" + b.source() + ")", 16)).append("  ")
                        .append(b.action().description()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }
}
