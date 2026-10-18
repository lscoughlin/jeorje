package org.github.lscoughlin.jeorje.cli;

import java.util.Map;
import java.util.TreeMap;
import org.github.lscoughlin.jeorje.core.config.LoadedConfig;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Prints the effective config with the source of each value. Secrets are hidden. */
final class ConfigPrinter {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonMapper PRETTY = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private ConfigPrinter() {}

    /** YAML. Each value ends with a comment that names its source. */
    static String yaml(LoadedConfig loaded) {
        var sb = new StringBuilder();
        walk(loaded, loaded.redactedTree(), "", 0, sb);
        return sb.toString();
    }

    /** JSON with the keys {@code config} and {@code sources}. */
    static String json(LoadedConfig loaded) {
        var sources = new TreeMap<String, String>();
        flatten(loaded.redactedTree(), "", sources, loaded);
        var root = new java.util.LinkedHashMap<String, Object>();
        root.put("config", loaded.redactedTree());
        root.put("sources", sources);
        return PRETTY.writeValueAsString(root);
    }

    @SuppressWarnings("unchecked")
    private static void flatten(Map<String, Object> in, String prefix, Map<String, String> out, LoadedConfig loaded) {
        in.forEach((k, v) -> {
            var path = prefix.isEmpty() ? k : prefix + "." + k;
            if (v instanceof Map<?, ?> m && !m.isEmpty()) {
                flatten((Map<String, Object>) m, path, out, loaded);
            } else {
                out.put(path, loaded.sourceOf(path).describe());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void walk(LoadedConfig loaded, Map<String, Object> map, String prefix, int depth, StringBuilder sb) {
        var pad = "  ".repeat(depth);
        for (var entry : map.entrySet()) {
            var path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            var value = entry.getValue();
            if (value instanceof Map<?, ?> m && !m.isEmpty()) {
                sb.append(pad).append(entry.getKey()).append(":\n");
                walk(loaded, (Map<String, Object>) m, path, depth + 1, sb);
            } else {
                sb.append(pad).append(entry.getKey()).append(": ").append(scalar(value))
                        .append("  # ").append(loaded.sourceOf(path).describe()).append('\n');
            }
        }
    }

    private static String scalar(Object value) {
        return switch (value) {
            case null -> "null";
            case String s when s.matches("[A-Za-z0-9_./~$@{}-][A-Za-z0-9 _./~$@{}-]*") && !s.endsWith(" ")
                    && !s.matches("(?i)true|false|null|yes|no|on|off|[-+]?[0-9.]+") -> s;
            case Number n -> n.toString();
            case Boolean b -> b.toString();
            default -> JSON.writeValueAsString(value);
        };
    }
}
