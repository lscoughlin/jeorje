package org.github.lscoughlin.jeorje.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Restricted mode for the project config of a project that the user does not trust. It removes the
 * keys that could widen permissions or change behavior. The list is in the Trust section of the
 * instructions design document.
 */
final class RestrictedMode {

    /** A path ending in ".*" matches the whole subtree. */
    static final List<String> IGNORED = List.of("permissions.extraDirectories", "permissions.webfetch.allow",
            "tools.extensions", "tools.bash.shell", "prompts.*", "skills.dirs", "skills.compat",
            "skills.honorAllowedTools", "agents.dirs", "editor.syntax.dirs", "swarm.*", "llm.baseUrl",
            "llm.headers", "models.*", "mcp.*");

    private RestrictedMode() {
    }

    /** Removes the ignored keys from the layer tree and returns one warning for each. */
    @SuppressWarnings("unchecked")
    static List<String> restrict(Map<String, Object> layer, String origin) {
        var warnings = new ArrayList<String>();
        for (var pattern : IGNORED) {
            var subtree = pattern.endsWith(".*");
            var path = subtree ? pattern.substring(0, pattern.length() - 2) : pattern;
            if (remove(layer, path.split("\\."), 0)) {
                warnings.add("Ignored key " + (subtree ? path + ".*" : path) + " in " + origin
                        + ": the project is not trusted.");
            }
        }
        if (layer.get("permissions") instanceof Map<?, ?> p
                && ((Map<String, Object>) p).get("rules") instanceof List<?> rules) {
            var kept = new ArrayList<Object>();
            for (var rule : rules) {
                if (rule instanceof Map<?, ?> m && "allow".equals(String.valueOf(m.get("decision")))) {
                    warnings.add("Ignored key permissions.rules entry with decision allow (tool "
                            + m.get("tool") + ") in " + origin + ": the project is not trusted.");
                } else {
                    kept.add(rule);
                }
            }
            ((Map<String, Object>) p).put("rules", kept);
        }
        return warnings;
    }

    @SuppressWarnings("unchecked")
    private static boolean remove(Map<String, Object> node, String[] path, int index) {
        var key = path[index];
        if (!node.containsKey(key)) {
            return false;
        }
        if (index == path.length - 1) {
            node.remove(key);
            return true;
        }
        if (node.get(key) instanceof Map<?, ?> child) {
            var removed = remove((Map<String, Object>) child, path, index + 1);
            if (removed && child.isEmpty()) {
                node.remove(key);
            }
            return removed;
        }
        return false;
    }
}
