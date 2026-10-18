package org.github.lscoughlin.jeorje.core.instructions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Scans skill directories and holds the skills and the diagnostics. */
public final class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);
    public static final int MAX_DEPTH = 6;
    public static final int MAX_DIRECTORIES = 2_000;
    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private final Map<String, Skill> skills;
    private final List<Diagnostic> diagnostics;

    private SkillRegistry(Map<String, Skill> skills, List<Diagnostic> diagnostics) {
        this.skills = skills;
        this.diagnostics = diagnostics;
    }

    /** Scans the roots in the given order. The first skill with a name wins. */
    public static SkillRegistry scan(List<Scopes.Root> roots) {
        return scan(roots, MAX_DEPTH, MAX_DIRECTORIES);
    }

    static SkillRegistry scan(List<Scopes.Root> roots, int maxDepth, int maxDirectories) {
        var skills = new LinkedHashMap<String, Skill>();
        var diagnostics = new ArrayList<Diagnostic>();
        for (var root : roots) {
            if (!Files.isDirectory(root.dir())) {
                continue;
            }
            var counter = new int[] {0};
            walk(root, root.dir(), 0, maxDepth, maxDirectories, counter, skills, diagnostics);
            if (counter[0] >= maxDirectories) {
                diagnostics.add(Diagnostic.warning(root.dir(), "Scan stopped after " + maxDirectories + " directories."));
            }
        }
        diagnostics.forEach(d -> log.atWarn().log(() -> d.file() + ": " + d.message()));
        return new SkillRegistry(skills, List.copyOf(diagnostics));
    }

    private static void walk(Scopes.Root root, Path dir, int depth, int maxDepth, int maxDirs, int[] counter,
                             Map<String, Skill> skills, List<Diagnostic> diagnostics) {
        if (counter[0] >= maxDirs) {
            return;
        }
        counter[0]++;
        var skillFile = dir.resolve("SKILL.md");
        if (Files.isRegularFile(skillFile)) {
            load(root, skillFile, skills, diagnostics);
            return;
        }
        if (depth >= maxDepth) {
            return;
        }
        List<Path> children;
        try (var stream = Files.list(dir)) {
            children = stream.filter(Files::isDirectory)
                    .filter(p -> {
                        var n = p.getFileName().toString();
                        return !n.equals(".git") && !n.equals("node_modules");
                    })
                    .sorted().toList();
        } catch (IOException e) {
            diagnostics.add(Diagnostic.warning(dir, "Cannot list directory: " + e.getMessage()));
            return;
        }
        for (var child : children) {
            walk(root, child, depth + 1, maxDepth, maxDirs, counter, skills, diagnostics);
        }
    }

    private static void load(Scopes.Root root, Path file, Map<String, Skill> skills, List<Diagnostic> diagnostics) {
        String text;
        try {
            text = TextFiles.read(file);
        } catch (IOException e) {
            diagnostics.add(Diagnostic.error(file, "Cannot read file: " + e.getMessage()));
            return;
        }
        var parsed = Frontmatter.parse(text);
        if (parsed.error() != null) {
            diagnostics.add(Diagnostic.error(file, "Invalid YAML frontmatter: " + parsed.error()));
            return;
        }
        if (parsed.repaired()) {
            diagnostics.add(Diagnostic.warning(file, "Frontmatter needed quotes around a value with a colon."));
        }
        var fields = parsed.fields();
        var description = Frontmatter.string(fields, "description");
        if (description == null || description.isEmpty()) {
            diagnostics.add(Diagnostic.error(file, "Skipped: the description is missing or empty."));
            return;
        }
        if (description.length() > 1024) {
            diagnostics.add(Diagnostic.warning(file, "The description is longer than 1,024 characters."));
        }
        var folder = file.getParent().getFileName().toString();
        var name = Frontmatter.string(fields, "name");
        if (name == null || name.isEmpty()) {
            name = folder;
            diagnostics.add(Diagnostic.warning(file, "The name is missing. Used the folder name '" + folder + "'."));
        } else {
            if (name.length() > 64) {
                diagnostics.add(Diagnostic.warning(file, "The name is longer than 64 characters."));
            }
            if (!VALID_NAME.matcher(name).matches()) {
                diagnostics.add(Diagnostic.warning(file, "The name '" + name
                        + "' has characters that the specification does not allow."));
            }
            if (!name.equals(folder)) {
                diagnostics.add(Diagnostic.warning(file, "The name '" + name + "' does not match the folder '" + folder + "'."));
            }
        }
        var body = parsed.body();
        var lines = body.isEmpty() ? 0 : body.split("\n", -1).length;
        if (lines > 500 || body.length() / 4 > 5_000) {
            diagnostics.add(Diagnostic.warning(file, "The body is large (" + lines + " lines). Consider smaller skills."));
        }
        var compat = Frontmatter.string(fields, "compatibility");
        if (compat != null && compat.length() > 500) {
            diagnostics.add(Diagnostic.warning(file, "The compatibility text is longer than 500 characters."));
        }
        var metadata = new LinkedHashMap<String, String>();
        if (fields.get("metadata") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> metadata.put(String.valueOf(k), String.valueOf(v)));
        }
        var disable = "true".equalsIgnoreCase(String.valueOf(fields.get("disable-model-invocation")));
        var skill = new Skill(name, description, file.toAbsolutePath(), root.scope(), body,
                Frontmatter.string(fields, "license"), compat, Map.copyOf(metadata),
                Frontmatter.string(fields, "allowed-tools"), disable);
        var existing = skills.get(name);
        if (existing != null) {
            diagnostics.add(Diagnostic.warning(file, "Skill '" + name + "' is skipped: the skill in "
                    + existing.location() + " has precedence."));
            return;
        }
        skills.put(name, skill);
    }

    public List<Skill> skills() {
        return List.copyOf(skills.values());
    }

    public java.util.Optional<Skill> find(String name) {
        return java.util.Optional.ofNullable(skills.get(name));
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    /** Diagnostics for tool names in allowed-tools that are not Jeorje tools. */
    public List<Diagnostic> allowedToolsDiagnostics(java.util.Set<String> knownTools) {
        var out = new ArrayList<Diagnostic>();
        for (var s : skills.values()) {
            var parsed = AllowedTools.parse(s.allowedTools(), knownTools);
            if (!parsed.ignored().isEmpty()) {
                out.add(Diagnostic.info(s.location(), "allowed-tools names ignored: " + String.join(", ", parsed.ignored())));
            }
        }
        return out;
    }

    /** Skills that the model may see: not hidden by the flag, a DENY rule, or the config. */
    public List<Skill> modelVisible(java.util.Set<String> hiddenNames) {
        return skills.values().stream()
                .filter(s -> !s.disableModelInvocation() && !hiddenNames.contains(s.name()))
                .collect(Collectors.toList());
    }

    /** Names of skills that clash with a built-in slash command. They are only available by the skill tool. */
    public List<Diagnostic> builtInClashes(java.util.Set<String> builtIns) {
        return skills.values().stream().filter(s -> builtIns.contains(s.name()))
                .map(s -> Diagnostic.warning(s.location(), "The skill name '" + s.name()
                        + "' is a built-in command. The skill is available only through the skill tool."))
                .toList();
    }
}
