package org.github.lscoughlin.jeorje.ui.keys;

import java.util.Map;

/**
 * The key settings of one configuration layer. A value is one key (a string), a list of keys, or {@code none}
 * (the string {@code none}, an empty list, or null).
 *
 * @param source the name of the layer, for example {@code user config} or {@code project config}
 * @param ui the {@code ui.keys} map
 * @param editor the {@code editor.keys} map
 */
public record KeyLayer(String source, Map<String, ?> ui, Map<String, ?> editor) {

    public KeyLayer {
        ui = ui == null ? Map.of() : ui;
        editor = editor == null ? Map.of() : editor;
    }

    public Map<String, ?> keys(KeyScope scope) {
        return scope == KeyScope.UI ? ui : editor;
    }
}
