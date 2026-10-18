package org.github.lscoughlin.jeorje.ui.keys;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The effective key bindings: the defaults with the configuration layers applied and validated. */
public final class KeyBindings {

    private static final Logger log = LoggerFactory.getLogger(KeyBindings.class);

    /** The source name of a default binding. */
    public static final String DEFAULT_SOURCE = "default";

    /** The Ctrl keys of the JLine emacs mode on the input line, with the name of each editing command. */
    private static final Map<Character, String> EDITING_KEYS = Map.ofEntries(
            Map.entry('a', "beginning-of-line"), Map.entry('b', "backward-char"),
            Map.entry('e', "end-of-line"), Map.entry('f', "forward-char"),
            Map.entry('k', "kill-line"), Map.entry('n', "down-history"),
            Map.entry('p', "up-history"), Map.entry('r', "history-incremental-search-backward"),
            Map.entry('t', "transpose-chars"), Map.entry('u', "kill-whole-line"),
            Map.entry('w', "backward-kill-word"), Map.entry('y', "yank"));

    /**
     * The effective keys of one action.
     *
     * @param action the action
     * @param keys the keys, empty when the user removed them
     * @param source {@code default} or the name of the layer that set the keys
     */
    public record Binding(Action action, List<KeySpec> keys, String source) {

        public Binding {
            keys = List.copyOf(keys);
        }

        /** The keys as text for menus and help, or {@code none}. */
        public String display() {
            return keys.isEmpty() ? "none" : keys.stream().map(KeySpec::toString).collect(Collectors.joining(", "));
        }
    }

    private final Map<KeyScope, Map<String, Binding>> bindings = new EnumMap<>(KeyScope.class);
    private final List<String> warnings;

    private KeyBindings(Map<KeyScope, Map<String, Binding>> bindings, List<String> warnings) {
        this.bindings.putAll(bindings);
        this.warnings = List.copyOf(warnings);
    }

    /** The default bindings. */
    public static KeyBindings defaults() {
        return resolve(List.of());
    }

    /**
     * Applies the layers to the defaults and validates the result.
     *
     * @param layers the layers from the lowest to the highest priority, for example user then project
     * @throws KeyConfigException when a rule fails
     */
    public static KeyBindings resolve(List<KeyLayer> layers) {
        return resolve(ActionRegistry.defaults(), layers);
    }

    public static KeyBindings resolve(ActionRegistry registry, List<KeyLayer> layers) {
        var errors = new ArrayList<String>();
        var result = new EnumMap<KeyScope, Map<String, Binding>>(KeyScope.class);
        for (var scope : KeyScope.values()) {
            var map = new LinkedHashMap<String, Binding>();
            var known = registry.byId(scope);
            known.forEach((id, action) -> map.put(id, new Binding(action, action.defaults(), DEFAULT_SOURCE)));
            for (var layer : layers) {
                layer.keys(scope).forEach((id, value) -> {
                    var action = known.get(id);
                    if (action == null) {
                        errors.add("Unknown action '" + id + "' in " + scope.configKey() + " (" + layer.source()
                                + ")." + hint(registry, scope, id));
                        return;
                    }
                    try {
                        map.put(id, new Binding(action, parseValue(value), layer.source()));
                    } catch (IllegalArgumentException e) {
                        errors.add("Action '" + id + "' in " + scope.configKey() + " (" + layer.source() + "): "
                                + e.getMessage());
                    }
                });
            }
            result.put(scope, map);
        }
        var warnings = new ArrayList<String>();
        validate(result, errors);
        if (!errors.isEmpty()) {
            throw new KeyConfigException(errors);
        }
        warnEditingKeys(result.get(KeyScope.UI), warnings);
        warnings.forEach(w -> log.atWarn().log(() -> w));
        return new KeyBindings(result, warnings);
    }

    private static String hint(ActionRegistry registry, KeyScope scope, String id) {
        var other = scope == KeyScope.UI ? KeyScope.EDITOR : KeyScope.UI;
        return registry.find(other, id).isPresent() ? " That action belongs to " + other.configKey() + "." : "";
    }

    private static List<KeySpec> parseValue(Object value) {
        List<?> items = switch (value) {
            case null -> List.of();
            case List<?> list -> list;
            default -> List.of(value);
        };
        var keys = new ArrayList<KeySpec>();
        for (var item : items) {
            var text = item == null ? "none" : item.toString().trim();
            if (text.equalsIgnoreCase("none") || text.isEmpty() && items.size() == 1) {
                continue;
            }
            var spec = KeySpec.parse(text);
            if (!keys.contains(spec)) {
                keys.add(spec);
            }
        }
        return keys;
    }

    private static void validate(Map<KeyScope, Map<String, Binding>> all, List<String> errors) {
        for (var scope : KeyScope.values()) {
            var scopeBindings = all.get(scope);
            for (var binding : scopeBindings.values()) {
                if (binding.action().required() && binding.keys().isEmpty()) {
                    errors.add("The action '" + binding.action().id() + "' must keep at least one key. The value "
                            + "'none' is not allowed in " + scope.configKey() + ".");
                }
            }
            for (var context : KeyContext.values()) {
                if (context.scope() == scope) {
                    checkConflicts(scope, context, scopeBindings.values().stream()
                            .filter(b -> b.action().context() == context).toList(), errors);
                }
            }
        }
    }

    private static void checkConflicts(KeyScope scope, KeyContext context, List<Binding> list, List<String> errors) {
        for (var i = 0; i < list.size(); i++) {
            for (var j = i + 1; j < list.size(); j++) {
                var a = list.get(i);
                var b = list.get(j);
                for (var ka : a.keys()) {
                    for (var kb : b.keys()) {
                        if (ka.equals(kb)) {
                            errors.add("The actions '" + a.action().id() + "' and '" + b.action().id()
                                    + "' both use the key '" + ka + "' in " + scope.configKey() + " ("
                                    + context.title() + ").");
                        } else if (ka.isPrefixOf(kb) || kb.isPrefixOf(ka)) {
                            var shorter = ka.isPrefixOf(kb) ? ka : kb;
                            var longer = shorter == ka ? kb : ka;
                            var shortAction = shorter == ka ? a : b;
                            var longAction = shorter == ka ? b : a;
                            errors.add("The key '" + shorter + "' of the action '" + shortAction.action().id()
                                    + "' is also the first key of the chord '" + longer + "' of the action '"
                                    + longAction.action().id() + "' in " + scope.configKey() + " ("
                                    + context.title() + "). A key cannot be a complete binding and a chord prefix.");
                        }
                    }
                }
            }
        }
    }

    private static void warnEditingKeys(Map<String, Binding> ui, List<String> warnings) {
        for (var binding : ui.values()) {
            if (binding.action().context() != KeyContext.CHAT) {
                continue;
            }
            for (var key : binding.keys()) {
                if (key.keys().size() != 1) {
                    continue;
                }
                var k = key.keys().getFirst();
                if (k.ctrl() && !k.alt() && !k.shift() && !k.isNamed()) {
                    var editing = EDITING_KEYS.get(k.base().charAt(0));
                    if (editing != null) {
                        warnings.add("The action '" + binding.action().id() + "' uses " + k
                                + ". It replaces the input line editing key '" + editing + "'.");
                    }
                }
            }
        }
    }

    /** Warnings from the validation. Jeorje logs each one when it resolves the bindings. */
    public List<String> warnings() {
        return warnings;
    }

    /** The bindings of one scope in table order. */
    public List<Binding> bindings(KeyScope scope) {
        return List.copyOf(bindings.get(scope).values());
    }

    /** The bindings of one context in table order. */
    public List<Binding> bindings(KeyContext context) {
        return bindings.get(context.scope()).values().stream()
                .filter(b -> b.action().context() == context).toList();
    }

    public Optional<Binding> binding(KeyScope scope, String id) {
        return Optional.ofNullable(bindings.get(scope).get(id));
    }

    /** The current keys of an action as text, for menus and the status bar. Returns {@code none} for no keys. */
    public String display(KeyScope scope, String id) {
        return binding(scope, id).map(Binding::display).orElse("none");
    }
}
