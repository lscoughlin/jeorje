package org.github.lscoughlin.jeorje.core.instructions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Loads subagent definitions from {@code agents/} directories. */
public final class AgentTypeRegistry {

    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private final Map<String, AgentType> types;
    private final List<Diagnostic> diagnostics;

    private AgentTypeRegistry(Map<String, AgentType> types, List<Diagnostic> diagnostics) {
        this.types = types;
        this.diagnostics = diagnostics;
    }

    public static AgentTypeRegistry scan(List<Scopes.Root> roots) {
        var types = new LinkedHashMap<String, AgentType>();
        var diagnostics = new ArrayList<Diagnostic>();
        for (var root : roots) {
            for (var file : MarkdownItems.list(root.dir(), diagnostics)) {
                String text;
                try {
                    text = TextFiles.read(file);
                } catch (IOException e) {
                    diagnostics.add(Diagnostic.error(file, "Cannot read file: " + e.getMessage()));
                    continue;
                }
                var parsed = Frontmatter.parse(text);
                if (parsed.error() != null) {
                    diagnostics.add(Diagnostic.error(file, "Invalid YAML frontmatter: " + parsed.error()));
                    continue;
                }
                var f = parsed.fields();
                var description = Frontmatter.string(f, "description");
                if (description == null || description.isEmpty()) {
                    diagnostics.add(Diagnostic.error(file, "Skipped: the description is missing or empty."));
                    continue;
                }
                var fileName = file.getFileName().toString();
                var name = Frontmatter.string(f, "name");
                if (name == null || name.isEmpty()) {
                    name = fileName.substring(0, fileName.length() - 3);
                    diagnostics.add(Diagnostic.warning(file, "The name is missing. Used the file name '" + name + "'."));
                } else if (!VALID_NAME.matcher(name).matches() || name.length() > 64) {
                    diagnostics.add(Diagnostic.warning(file, "The name '" + name + "' does not follow the name rules."));
                }
                var isolation = Frontmatter.string(f, "isolation");
                if (isolation != null) {
                    isolation = isolation.toLowerCase(Locale.ROOT);
                    if (!isolation.equals("worktree") && !isolation.equals("shared")) {
                        diagnostics.add(Diagnostic.warning(file, "Unknown isolation '" + isolation + "' is ignored."));
                        isolation = null;
                    }
                }
                var existing = types.get(name);
                if (existing != null) {
                    diagnostics.add(Diagnostic.warning(file, "Agent type '" + name + "' is skipped: the type in "
                            + existing.file() + " has precedence."));
                    continue;
                }
                types.put(name, new AgentType(name, description, tools(f.get("tools")),
                        Frontmatter.string(f, "model"), isolation, parsed.body().strip(),
                        file.toAbsolutePath(), root.scope()));
            }
        }
        return new AgentTypeRegistry(types, List.copyOf(diagnostics));
    }

    private static List<String> tools(Object value) {
        return switch (value) {
            case null -> null;
            case List<?> list -> list.stream().map(o -> String.valueOf(o).strip()).toList();
            case String s -> List.of(s.split("[,\\s]+")).stream().filter(t -> !t.isBlank()).toList();
            default -> null;
        };
    }

    public List<AgentType> types() {
        return List.copyOf(types.values());
    }

    public Optional<AgentType> find(String name) {
        return Optional.ofNullable(types.get(name));
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }
}
