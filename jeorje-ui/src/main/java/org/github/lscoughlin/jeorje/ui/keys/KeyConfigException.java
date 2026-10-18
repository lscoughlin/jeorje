package org.github.lscoughlin.jeorje.ui.keys;

import java.util.List;

/** The key configuration is not valid. The message lists every problem. */
public class KeyConfigException extends RuntimeException {

    private final List<String> errors;

    public KeyConfigException(List<String> errors) {
        super("Invalid key configuration:\n- " + String.join("\n- ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
