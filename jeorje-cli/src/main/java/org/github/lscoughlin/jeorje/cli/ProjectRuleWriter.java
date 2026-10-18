package org.github.lscoughlin.jeorje.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Writes a permission rule into the project config {@code ${CWD}/.jeorje/config.yaml}. The writer creates the
 * file when it is missing and keeps all other keys. It edits the text of the file, so the comments stay. If the
 * file has a shape that the text edit does not handle, the writer falls back to a rewrite of the parsed data.
 * That rewrite keeps the keys but not the comments.
 */
public final class ProjectRuleWriter {
    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final Pattern PERMISSIONS = Pattern.compile("^permissions:\\s*(\\{\\s*\\})?\\s*(#.*)?$");
    private static final Pattern RULES = Pattern.compile("^(\\s+)rules:\\s*(\\[\\s*\\])?\\s*(#.*)?$");
    private static final Pattern ITEM = Pattern.compile("^(\\s*)-\\s.*$");
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_.-]+");

    private ProjectRuleWriter() {}

    /** The path of the project config of a working directory. */
    public static Path configFile(Path cwd) {
        return cwd.resolve(".jeorje").resolve("config.yaml");
    }

    /**
     * Adds an {@code allow} rule for a tool. Does nothing when the same rule is already there.
     *
     * @return true when the writer changed the file
     */
    public static boolean allow(Path cwd, String tool) throws IOException {
        var file = configFile(cwd);
        var text = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        var tree = parse(text);
        if (hasRule(tree, tool)) return false;
        var edited = edit(text, tool);
        if (edited == null || !hasRule(parse(edited), tool) || ruleCount(parse(edited)) != ruleCount(tree) + 1) {
            edited = rewrite(tree, tool);
        }
        Files.createDirectories(file.getParent());
        var temp = Files.createTempFile(file.getParent(), "config", ".tmp");
        try {
            Files.writeString(temp, edited, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String text) throws IOException {
        if (text.isBlank()) return new LinkedHashMap<>();
        try {
            var value = YAML.readValue(text, Object.class);
            if (value == null) return new LinkedHashMap<>();
            if (value instanceof Map<?, ?> m) return new LinkedHashMap<>((Map<String, Object>) m);
        } catch (RuntimeException e) {
            throw new IOException("The project config is not valid YAML: " + e.getMessage(), e);
        }
        throw new IOException("The project config is not a mapping.");
    }

    private static int ruleCount(Map<String, Object> tree) {
        return tree.get("permissions") instanceof Map<?, ?> p && p.get("rules") instanceof List<?> r ? r.size() : 0;
    }

    private static boolean hasRule(Map<String, Object> tree, String tool) {
        if (!(tree.get("permissions") instanceof Map<?, ?> permissions)) return false;
        if (!(permissions.get("rules") instanceof List<?> rules)) return false;
        return rules.stream().anyMatch(r -> r instanceof Map<?, ?> m && tool.equals(m.get("tool"))
                && m.get("pattern") == null && "allow".equals(String.valueOf(m.get("decision"))));
    }

    @SuppressWarnings("unchecked")
    private static String rewrite(Map<String, Object> tree, String tool) {
        var permissions = tree.get("permissions") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<String, Object>();
        var rules = permissions.get("rules") instanceof List<?> l ? new ArrayList<Object>(l) : new ArrayList<Object>();
        var rule = new LinkedHashMap<String, Object>();
        rule.put("tool", tool);
        rule.put("decision", "allow");
        rules.add(rule);
        permissions.put("rules", rules);
        tree.put("permissions", permissions);
        return YAML.writeValueAsString(tree);
    }

    /** Edits the text. Returns null when the shape of the file is not handled. */
    private static String edit(String text, String tool) {
        var name = PLAIN.matcher(tool).matches() ? tool : "\"" + tool.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        var lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
        var head = -1;
        for (var i = 0; i < lines.size(); i++) {
            if (PERMISSIONS.matcher(lines.get(i)).matches()) {
                head = i;
                break;
            }
        }
        if (head < 0 && lines.stream().anyMatch(l -> l.startsWith("permissions:"))) return null;
        if (head < 0) {
            lines.add("permissions:");
            lines.add("  rules:");
            lines.add("    - tool: " + name);
            lines.add("      decision: allow");
            return String.join("\n", lines) + "\n";
        }
        var inline = lines.get(head).replaceFirst("\\{\\s*\\}", "").stripTrailing();
        // The end of the block of permissions.
        var end = head + 1;
        while (end < lines.size()) {
            var line = lines.get(end);
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("\t") && !line.startsWith("#")) break;
            end++;
        }
        var rulesAt = -1;
        for (var i = head + 1; i < end; i++) {
            if (RULES.matcher(lines.get(i)).matches()) {
                rulesAt = i;
                break;
            }
        }
        if (lines.get(head).contains("{")) {
            lines.set(head, inline);
        }
        if (rulesAt < 0) {
            var indent = "  ";
            for (var i = head + 1; i < end; i++) {
                if (!lines.get(i).isBlank() && !lines.get(i).startsWith("#")) {
                    indent = lines.get(i).substring(0, lines.get(i).length() - lines.get(i).stripLeading().length());
                    break;
                }
            }
            lines.add(head + 1, indent + "rules:");
            lines.add(head + 2, indent + "  - tool: " + name);
            lines.add(head + 3, indent + "    decision: allow");
            return String.join("\n", lines) + "\n";
        }
        var rulesMatcher = RULES.matcher(lines.get(rulesAt));
        if (!rulesMatcher.matches()) return null;
        var keyIndent = rulesMatcher.group(1);
        if (rulesMatcher.group(2) != null) lines.set(rulesAt, keyIndent + "rules:");
        var itemIndent = keyIndent + "  ";
        for (var i = rulesAt + 1; i < end; i++) {
            var item = ITEM.matcher(lines.get(i));
            if (item.matches()) {
                itemIndent = item.group(1);
                break;
            }
            if (!lines.get(i).isBlank() && !lines.get(i).strip().startsWith("#")) break;
        }
        lines.add(rulesAt + 1, itemIndent + "- tool: " + name);
        lines.add(rulesAt + 2, itemIndent + "  decision: allow");
        return String.join("\n", lines) + "\n";
    }
}
