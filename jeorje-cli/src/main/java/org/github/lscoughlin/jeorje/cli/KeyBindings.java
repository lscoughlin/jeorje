package org.github.lscoughlin.jeorje.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.config.ConfigSource;
import org.github.lscoughlin.jeorje.core.config.LoadedConfig;

/** The effective key bindings: the defaults of the design, changed by {@code ui.keys} and {@code editor.keys}. */
final class KeyBindings {

    static final Map<String, String> CHAT = new LinkedHashMap<>();
    static final Map<String, String> EDITOR = new LinkedHashMap<>();

    static {
        put(CHAT, "send", "Enter", "newline", "Alt-Enter", "interrupt", "Ctrl-C", "exit", "Ctrl-D",
                "agents", "Ctrl-X a", "files", "Ctrl-X f", "expandResult", "Ctrl-X o", "redraw", "Ctrl-L",
                "follow", "Ctrl-X b", "copySelection", "Ctrl-X c", "scrollUp", "PgUp", "scrollDown", "PgDn",
                "browserOpen", "Right", "browserClose", "Left", "browserEdit", "Enter", "browserAdd", "a",
                "browserFilter", "/", "browserHidden", ".", "browserExit", "Esc");
        put(EDITOR, "menu", "F10", "menuFile", "Alt-F", "menuEdit", "Alt-E", "menuSearch", "Alt-S",
                "menuView", "Alt-V", "menuOptions", "Alt-O", "menuHelp", "Alt-H", "save", "Ctrl-S",
                "close", "Alt-X", "undo", "Ctrl-Z", "redo", "Ctrl-Y", "cut", "Shift-Del", "copy", "Ctrl-Ins",
                "paste", "Shift-Ins", "selectAll", "Ctrl-A", "find", "Ctrl-Q f", "repeatFind", "F3",
                "change", "Ctrl-Q a", "goToLine", "Ctrl-G", "help", "F1", "switchWindow", "F6",
                "toggleInsert", "Ins", "indent", "Tab", "unindent", "Shift-Tab", "wordLeft", "Ctrl-Left",
                "wordRight", "Ctrl-Right", "fileStart", "Ctrl-Home", "fileEnd", "Ctrl-End");
    }

    private KeyBindings() {}

    private static void put(Map<String, String> map, String... pairs) {
        for (var i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
    }

    /** One line for each action: {@code scope.action: keys  # source}. */
    static List<String> lines(LoadedConfig loaded) {
        var lines = new ArrayList<String>();
        section(lines, loaded, "ui.keys", CHAT, loaded.config().ui().keys());
        section(lines, loaded, "editor.keys", EDITOR, loaded.config().editor().keys());
        return lines;
    }

    private static void section(List<String> lines, LoadedConfig loaded, String scope, Map<String, String> defaults,
                                Map<String, Object> configured) {
        var all = new LinkedHashMap<String, Object>(defaults);
        all.putAll(configured);
        all.forEach((action, value) -> {
            var key = scope + "." + action;
            var source = configured.containsKey(action) ? loaded.sourceOf(key) : ConfigSource.DEFAULT;
            lines.add(key + ": " + render(value) + "  # " + source.describe());
        });
    }

    private static String render(Object value) {
        return value instanceof List<?> l ? (l.isEmpty() ? "none" : String.join(", ", l.stream().map(String::valueOf).toList()))
                : String.valueOf(value);
    }
}
