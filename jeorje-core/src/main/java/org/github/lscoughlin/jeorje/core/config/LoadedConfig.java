package org.github.lscoughlin.jeorje.core.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The result of a load: the config, the source of each value, and the warnings.
 *
 * @param config   the immutable config
 * @param tree     the effective values by key, with secrets still in place
 * @param sources  the source of each leaf value, by dotted key
 * @param warnings the warnings that the load logged
 * @param trusted  true when the project config was read in full
 */
public record LoadedConfig(JeorjeConfig config, Map<String, Object> tree, Map<String, ConfigSource> sources,
                           List<String> warnings, boolean trusted) {

    public LoadedConfig {
        sources = Map.copyOf(sources);
        warnings = List.copyOf(warnings);
        tree = JeorjeConfig.freezeMap(tree);
    }

    public ConfigSource sourceOf(String key) {
        return sources.getOrDefault(key, ConfigSource.DEFAULT);
    }

    /** The effective values with secrets replaced by the mask. */
    public Map<String, Object> redactedTree() {
        return redact(tree, "");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> redact(Map<String, Object> in, String prefix) {
        var out = new LinkedHashMap<String, Object>();
        in.forEach((k, v) -> {
            var path = prefix.isEmpty() ? k : prefix + "." + k;
            if (v instanceof Map<?, ?> m) {
                out.put(k, redact((Map<String, Object>) m, path));
            } else if (ConfigDefaults.isSecret(path) && v instanceof String s && !s.isEmpty()) {
                out.put(k, SecretScrubber.MASK);
            } else {
                out.put(k, v);
            }
        });
        return out;
    }

    public String toYaml() {
        return YAMLMapper.builder().build().writeValueAsString(redactedTree());
    }

    public String toJson() {
        return JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()
                .writeValueAsString(redactedTree());
    }

    /** One line for each leaf: {@code key: value  # source}. Secrets are hidden. */
    public List<String> describe() {
        var lines = new ArrayList<String>();
        var leaves = new TreeMap<String, Object>();
        collect(redactedTree(), "", leaves);
        leaves.forEach((k, v) -> lines.add(k + ": " + v + "  # " + sourceOf(k).describe()));
        return lines;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Map<String, Object> in, String prefix, Map<String, Object> out) {
        in.forEach((k, v) -> {
            var path = prefix.isEmpty() ? k : prefix + "." + k;
            if (v instanceof Map<?, ?> m && !m.isEmpty()) {
                collect((Map<String, Object>) m, path, out);
            } else {
                out.put(path, v);
            }
        });
    }
}
