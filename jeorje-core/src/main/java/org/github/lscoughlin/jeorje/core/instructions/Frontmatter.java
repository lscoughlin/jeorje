package org.github.lscoughlin.jeorje.core.instructions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Splits a markdown file into YAML frontmatter and body. Repairs unquoted values that have a colon. */
public final class Frontmatter {

    private static final ObjectMapper YAML = YAMLMapper.builder().build();

    /**
     * Result of parsing.
     *
     * @param fields   the frontmatter fields (empty when none)
     * @param body     the text after the frontmatter
     * @param repaired true when the colon repair was needed
     * @param error    YAML error text, or null
     * @param present  true when the file has a frontmatter block
     */
    public record Parsed(Map<String, Object> fields, String body, boolean repaired, String error, boolean present) {
    }

    private Frontmatter() {
    }

    public static Parsed parse(String text) {
        var normalized = text.startsWith("\uFEFF") ? text.substring(1) : text;
        var lines = normalized.split("\r?\n", -1);
        if (lines.length == 0 || !lines[0].stripTrailing().equals("---")) {
            return new Parsed(Map.of(), normalized, false, null, false);
        }
        var end = -1;
        for (var i = 1; i < lines.length; i++) {
            if (lines[i].stripTrailing().equals("---")) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            return new Parsed(Map.of(), normalized, false, "Frontmatter is not closed.", false);
        }
        var yamlLines = List.of(lines).subList(1, end);
        var body = String.join("\n", List.of(lines).subList(end + 1, lines.length));
        var yaml = String.join("\n", yamlLines);
        try {
            return new Parsed(read(yaml), body, false, null, true);
        } catch (RuntimeException first) {
            try {
                return new Parsed(read(repair(yamlLines)), body, true, null, true);
            } catch (RuntimeException second) {
                return new Parsed(Map.of(), body, false, String.valueOf(second.getMessage()), true);
            }
        }
    }

    private static Map<String, Object> read(String yaml) {
        if (yaml.isBlank()) {
            return Map.of();
        }
        var value = YAML.readValue(yaml, Object.class);
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        throw new IllegalArgumentException("Frontmatter is not a map.");
    }

    /** Wraps top-level scalar values that contain a colon followed by a space in double quotes. */
    static String repair(List<String> lines) {
        var out = new ArrayList<String>();
        for (var line : lines) {
            var colon = line.indexOf(':');
            if (colon <= 0 || Character.isWhitespace(line.charAt(0)) || line.startsWith("#")) {
                out.add(line);
                continue;
            }
            var key = line.substring(0, colon);
            var value = line.substring(colon + 1).strip();
            if (value.isEmpty() || !(value.contains(": ") || value.endsWith(":"))
                    || "\"'[{|>&*!#".indexOf(value.charAt(0)) >= 0) {
                out.add(line);
                continue;
            }
            out.add(key + ": \"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        }
        return String.join("\n", out);
    }

    public static String string(Map<String, Object> fields, String key) {
        var value = fields.get(key);
        return value == null ? null : String.valueOf(value).strip();
    }
}
