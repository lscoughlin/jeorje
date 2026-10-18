package org.github.lscoughlin.jeorje.ui.keys;

/** A section of the configuration that holds key bindings. */
public enum KeyScope {
    /** The {@code ui.keys} map: the chat screen and the file browser. */
    UI("ui.keys"),
    /** The {@code editor.keys} map. */
    EDITOR("editor.keys");

    private final String configKey;

    KeyScope(String configKey) {
        this.configKey = configKey;
    }

    /** The name of the map in the configuration. */
    public String configKey() {
        return configKey;
    }
}
