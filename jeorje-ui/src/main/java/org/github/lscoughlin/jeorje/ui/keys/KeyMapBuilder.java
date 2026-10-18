package org.github.lscoughlin.jeorje.ui.keys;

import java.util.ArrayList;
import java.util.List;
import org.jline.keymap.KeyMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds JLine {@link KeyMap} objects from the effective bindings. The value of each bound key is the action id. */
public final class KeyMapBuilder {

    private static final Logger log = LoggerFactory.getLogger(KeyMapBuilder.class);

    /** The prefix of the action id for the derived selection form of a movement action. */
    public static final String SELECT_PREFIX = "select:";

    private final KeyBindings bindings;
    private final boolean kitty;

    /**
     * @param bindings the effective bindings
     * @param kitty whether the terminal runs the Kitty keyboard protocol, so that its key forms are bound too
     */
    public KeyMapBuilder(KeyBindings bindings, boolean kitty) {
        this.bindings = bindings;
        this.kitty = kitty;
    }

    /**
     * Builds the key map of one context. In the editor, each movement key also gets a Shift form with the action id
     * {@code select:<id>}, unless that key belongs to another action.
     */
    public KeyMap<String> build(KeyContext context) {
        var map = new KeyMap<String>();
        var explicit = new ArrayList<KeySpec>();
        for (var binding : bindings.bindings(context)) {
            explicit.addAll(binding.keys());
        }
        for (var binding : bindings.bindings(context)) {
            for (var key : binding.keys()) {
                bind(map, binding.action().id(), key);
            }
        }
        if (context == KeyContext.EDITOR) {
            for (var binding : bindings.bindings(context)) {
                if (!ActionRegistry.MOVEMENT_ACTIONS.contains(binding.action().id())) {
                    continue;
                }
                for (var key : binding.keys()) {
                    var shifted = shifted(key);
                    if (shifted != null && !explicit.contains(shifted) && !blocked(explicit, shifted)) {
                        bind(map, SELECT_PREFIX + binding.action().id(), shifted);
                    }
                }
            }
        }
        return map;
    }

    private void bind(KeyMap<String> map, String action, KeySpec key) {
        var sequences = key.sequences(kitty);
        if (sequences.isEmpty()) {
            log.atWarn().log(() -> "The terminal cannot send the key '" + key + "' for the action '" + action
                    + "' without the Kitty keyboard protocol. Bind a second key to the action.");
            return;
        }
        map.bind(action, sequences);
    }

    /** The Shift form of a single movement key, or null when the key is a chord, already Shift, or not a named key. */
    private static KeySpec shifted(KeySpec key) {
        if (key.keys().size() != 1) {
            return null;
        }
        var k = key.keys().getFirst();
        if (k.shift() || !k.isNamed()) {
            return null;
        }
        return new KeySpec(List.of(new KeySpec.Key(k.base(), k.ctrl(), k.alt(), true)));
    }

    private static boolean blocked(List<KeySpec> explicit, KeySpec shifted) {
        return explicit.stream().anyMatch(e -> e.isPrefixOf(shifted) || shifted.isPrefixOf(e));
    }
}
