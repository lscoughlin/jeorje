package org.github.lscoughlin.jeorje.ui.keys;

import java.util.List;

/**
 * An action that a key can start.
 *
 * @param id the action id, as used in the configuration
 * @param context where the action is active
 * @param description what the action does
 * @param defaults the default keys
 * @param required whether the action must keep at least one key
 */
public record Action(String id, KeyContext context, String description, List<KeySpec> defaults, boolean required) {

    public Action {
        defaults = List.copyOf(defaults);
    }

    public KeyScope scope() {
        return context.scope();
    }
}
