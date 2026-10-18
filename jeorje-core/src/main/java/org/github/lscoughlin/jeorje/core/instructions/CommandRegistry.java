package org.github.lscoughlin.jeorje.core.instructions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Loads custom slash commands from {@code commands/} directories. */
public final class CommandRegistry {

    private final Map<String, Command> commands;
    private final List<Diagnostic> diagnostics;

    private CommandRegistry(Map<String, Command> commands, List<Diagnostic> diagnostics) {
        this.commands = commands;
        this.diagnostics = diagnostics;
    }

    /** Scans the roots in order. The first command with a name wins. Built-in names are skipped with a warning. */
    public static CommandRegistry scan(List<Scopes.Root> roots, Set<String> builtIns) {
        var commands = new LinkedHashMap<String, Command>();
        var diagnostics = new ArrayList<Diagnostic>();
        for (var root : roots) {
            for (var file : MarkdownItems.list(root.dir(), diagnostics)) {
                var name = nameOf(root.dir(), file);
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
                if (builtIns.contains(name)) {
                    diagnostics.add(Diagnostic.warning(file, "Skipped: '" + name + "' is a built-in command."));
                    continue;
                }
                var existing = commands.get(name);
                if (existing != null) {
                    diagnostics.add(Diagnostic.warning(file, "Command '" + name + "' is skipped: the command in "
                            + existing.file() + " has precedence."));
                    continue;
                }
                var f = parsed.fields();
                commands.put(name, new Command(name, Frontmatter.string(f, "description"),
                        Frontmatter.string(f, "argument-hint"), Frontmatter.string(f, "model"),
                        parsed.body().strip(), file.toAbsolutePath(), root.scope()));
            }
        }
        return new CommandRegistry(commands, List.copyOf(diagnostics));
    }

    /** The command name: the file name without .md. A file in a subfolder gets folder:name. */
    static String nameOf(Path root, Path file) {
        var rel = root.relativize(file);
        var parts = new ArrayList<String>();
        for (var part : rel) {
            parts.add(part.toString());
        }
        var last = parts.removeLast();
        parts.add(last.substring(0, last.length() - 3));
        return String.join(":", parts);
    }

    public List<Command> commands() {
        return List.copyOf(commands.values());
    }

    public Optional<Command> find(String name) {
        return Optional.ofNullable(commands.get(name));
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }
}
