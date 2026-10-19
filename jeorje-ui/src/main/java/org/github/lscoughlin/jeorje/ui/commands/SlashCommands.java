package org.github.lscoughlin.jeorje.ui.commands;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.github.lscoughlin.jeorje.core.compaction.ModelRoles;
import org.github.lscoughlin.jeorje.core.instructions.Diagnostic;
import org.github.lscoughlin.jeorje.core.instructions.Skill;
import org.github.lscoughlin.jeorje.ui.keys.KeyHelp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses slash command lines, completes command names, and runs the built-in commands, custom commands,
 * and skill invocations. Built-in commands win over custom commands and skills, with a warning.
 */
public final class SlashCommands {

    private static final Logger log = LoggerFactory.getLogger(SlashCommands.class);

    /** Names that other parts of the UI register ({@code /plan}, {@code /files}, and so on). They are reserved. */
    private static final Set<String> RESERVED = Set.of("plan", "implement", "worktrees", "merge", "discard",
            "files", "edit", "agents", "peers");

    /** One built-in command. */
    public record Builtin(String name, String usage, String description, Function<String, CommandResult> handler) {
    }

    private final CommandBackend backend;
    private final Map<String, Builtin> builtins = new LinkedHashMap<>();

    public SlashCommands(CommandBackend backend) {
        this.backend = backend;
        registerDefaults();
    }

    /** Adds or replaces a built-in command. Other UI parts use it for commands that this class does not own. */
    public void register(String name, String usage, String description, Function<String, CommandResult> handler) {
        builtins.put(name, new Builtin(name, usage, description, handler));
    }

    /** The names of all built-in commands and reserved names. */
    public Set<String> builtInNames() {
        var names = new TreeSet<>(builtins.keySet());
        names.addAll(RESERVED);
        return names;
    }

    /** Warnings about names that clash with a built-in command, and the diagnostics of the registries. */
    public List<String> warnings() {
        var names = builtInNames();
        var out = new ArrayList<String>();
        for (var d : backend.skills().builtInClashes(names)) {
            out.add(format(d));
        }
        for (var d : backend.commands().diagnostics()) {
            if (d.level() != Diagnostic.Level.INFO) {
                out.add(format(d));
            }
        }
        for (var c : backend.commands().commands()) {
            if (names.contains(c.name())) {
                out.add("The custom command '" + c.name() + "' is hidden by the built-in command.");
            }
            if (backend.skills().find(c.name()).isPresent()) {
                out.add("The skill '" + c.name() + "' is hidden by the custom command with the same name.");
            }
        }
        return out;
    }

    private static String format(Diagnostic d) {
        return d.level().name().toLowerCase(java.util.Locale.ROOT) + ": " + d.file() + ": " + d.message();
    }

    /** Runs a line of input. A line that is not a slash command is not handled. */
    public CommandResult execute(String line) {
        var parsed = ParsedCommand.parse(line);
        if (parsed.isEmpty()) {
            return CommandResult.NOT_A_COMMAND;
        }
        var name = parsed.get().name();
        var args = parsed.get().arguments();
        var builtin = builtins.get(name);
        if (builtin != null) {
            return builtin.handler().apply(args);
        }
        if (RESERVED.contains(name)) {
            return CommandResult.fail("The command /" + name + " is not available here.");
        }
        var custom = backend.commands().find(name);
        if (custom.isPresent()) {
            var command = custom.get();
            backend.sendPrompt(command.render(args), blankToNull(command.model()));
            return CommandResult.ok("");
        }
        var skill = backend.skills().find(name);
        if (skill.isPresent()) {
            backend.sendPrompt(skillPrompt(skill.get(), args), null);
            return CommandResult.ok("");
        }
        log.atDebug().log(() -> "Unknown slash command: " + name);
        return CommandResult.fail("Unknown command: /" + name + ". Type /help for the list.");
    }

    static String skillPrompt(Skill skill, String args) {
        var sb = new StringBuilder();
        sb.append("Use the skill '").append(skill.name()).append("'.\n");
        sb.append("Skill file: ").append(skill.location()).append("\n");
        sb.append("Skill directory: ").append(skill.directory()).append("\n\n");
        sb.append(skill.body().strip()).append('\n');
        if (args != null && !args.isBlank()) {
            sb.append("\nArguments: ").append(args.strip()).append('\n');
        }
        return sb.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    // Completion

    /**
     * Completes the input line. For a line with only a command name, it lists the matching command names.
     * For a line with a name and a space, it lists argument values for the commands that have a fixed set.
     */
    public List<Candidate> complete(String line) {
        if (line == null || !line.startsWith("/")) {
            return List.of();
        }
        var space = line.indexOf(' ');
        if (space < 0) {
            return completeNames(line.substring(1));
        }
        var name = line.substring(1, space);
        var argPrefix = line.substring(space + 1);
        var options = switch (name) {
            case "trust" -> List.of("revoke");
            case "compactor" -> List.of("off");
            case "advisor" -> List.of("none");
            default -> List.<String>of();
        };
        return options.stream().filter(o -> o.startsWith(argPrefix))
                .map(o -> new Candidate("/" + name + " " + o, "")).toList();
    }

    private List<Candidate> completeNames(String prefix) {
        var out = new ArrayList<Candidate>();
        var seen = new TreeSet<String>();
        for (var b : builtins.values()) {
            if (b.name().startsWith(prefix) && seen.add(b.name())) {
                out.add(new Candidate("/" + b.name(), b.description()));
            }
        }
        var names = builtInNames();
        for (var c : backend.commands().commands()) {
            if (!names.contains(c.name()) && c.name().startsWith(prefix) && seen.add(c.name())) {
                var hint = c.argumentHint() == null || c.argumentHint().isBlank() ? "" : " " + c.argumentHint();
                out.add(new Candidate("/" + c.name(), nullToEmpty(c.description()) + hint));
            }
        }
        for (var s : backend.skills().skills()) {
            if (!names.contains(s.name()) && s.name().startsWith(prefix) && seen.add(s.name())) {
                out.add(new Candidate("/" + s.name(), s.description()));
            }
        }
        out.sort(java.util.Comparator.comparing(Candidate::text));
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // Built-in commands

    private void registerDefaults() {
        register("help", "/help", "Show help", a -> CommandResult.ok(help()));
        register("model", "/model [slug]", "Show or set the default model", a -> role("model", a, false));
        register("compactor", "/compactor [slug|off]", "Show or set the compactor model",
                a -> role("compactor", a, true));
        register("advisor", "/advisor [slug|none]", "Set the advisor model (default " + DEFAULT_ADVISOR + "). none turns it off", a -> role("advisor", a, true));
        register("compact", "/compact", "Run compaction now", a -> CommandResult.ok(backend.compactNow()));
        register("pin", "/pin", "Pin the last message", a -> CommandResult.ok(backend.pinLast()));
        register("clear", "/clear", "Start a fresh conversation",
                a -> a.isBlank() ? CommandResult.ok(backend.clearConversation())
                        : CommandResult.fail("Usage: /clear"));
        register("recall", "/recall <query>", "Search the history and show the hits", this::recall);
        register("keys", "/keys", "Show the effective key bindings", a -> CommandResult.ok(KeyHelp.render(backend.keys())));
        register("init", "/init", "Ask the agent to write an AGENTS.md", a -> {
            backend.sendPrompt(INIT_PROMPT, null);
            return CommandResult.ok("");
        });
        register("instructions", "/instructions", "Show the AGENTS.md files that apply", a -> CommandResult.ok(instructions()));
        register("skills", "/skills", "List skills", a -> CommandResult.ok(skills()));
        register("commands", "/commands", "List custom commands", a -> CommandResult.ok(commands()));
        register("agent-types", "/agent-types", "List subagent definitions", a -> CommandResult.ok(agentTypes()));
        register("trust", "/trust [revoke]", "Trust the project, or revoke the trust", this::trust);
        register("cancel", "/cancel [name]", "Cancel the foreground agent or a named subagent",
                a -> CommandResult.ok(backend.cancel(a.isBlank() ? null : a)));
        register("back", "/back", "Return to the parent agent", a -> CommandResult.ok(backend.back()));
        register("quit", "/quit", "Exit", a -> {
            backend.quit();
            return CommandResult.ok("");
        });
    }

    private static final String INIT_PROMPT = """
            Write an AGENTS.md file for this project in the project root.
            Read the build files and the main source folders first.
            Describe the layout, the build and test commands, and the code conventions.
            Keep the file short and factual.
            """;

    private String help() {
        var sb = new StringBuilder("Commands\n");
        var width = builtins.values().stream().mapToInt(b -> b.usage().length()).max().orElse(0);
        for (var b : builtins.values()) {
            sb.append("  ").append(pad(b.usage(), width)).append("  ").append(b.description()).append('\n');
        }
        var customs = backend.commands().commands().stream()
                .filter(c -> !builtInNames().contains(c.name())).toList();
        if (!customs.isEmpty()) {
            sb.append("Custom commands\n");
            for (var c : customs) {
                sb.append("  /").append(c.name());
                if (c.argumentHint() != null && !c.argumentHint().isBlank()) {
                    sb.append(' ').append(c.argumentHint());
                }
                sb.append("  ").append(nullToEmpty(c.description())).append('\n');
            }
        }
        var skills = backend.skills().skills().stream().filter(s -> !builtInNames().contains(s.name())).toList();
        if (!skills.isEmpty()) {
            sb.append("Skills\n");
            skills.forEach(s -> sb.append("  /").append(s.name()).append("  ").append(s.description()).append('\n'));
        }
        var warnings = warnings();
        if (!warnings.isEmpty()) {
            sb.append("Warnings\n");
            warnings.forEach(w -> sb.append("  ").append(w).append('\n'));
        }
        return sb.toString().stripTrailing();
    }

    private static String pad(String s, int width) {
        return s + " ".repeat(Math.max(0, width - s.length()));
    }

    /** The advisor model that {@code /advisor} sets when it has no argument. */
    static final String DEFAULT_ADVISOR = "~deepseek/deepseek-pro-latest";

    private CommandResult role(String which, String arg0, boolean canTurnOff) {
        var arg = arg0;
        var roles = backend.roles();
        var current = switch (which) {
            case "model" -> roles.defaultModel();
            case "compactor" -> roles.compactor();
            default -> roles.advisor();
        };
        if (which.equals("advisor") && arg.isBlank()) {
            arg = DEFAULT_ADVISOR;
        }
        if (arg.isBlank()) {
            var shown = current == null || current.isBlank() ? "off" : current;
            return CommandResult.ok("The " + label(which) + " is " + shown + ".");
        }
        if (arg.chars().anyMatch(Character::isWhitespace)) {
            return CommandResult.fail("A model slug has no spaces: " + arg);
        }
        String value;
        if (arg.equalsIgnoreCase(which.equals("advisor") ? "none" : "off")) {
            if (!canTurnOff) {
                return CommandResult.fail("The default model cannot be off. Give a slug.");
            }
            value = null;
        } else {
            value = arg;
        }
        var updated = switch (which) {
            case "model" -> new ModelRoles(value, roles.compactor(), roles.advisor());
            case "compactor" -> new ModelRoles(roles.defaultModel(), value, roles.advisor());
            default -> new ModelRoles(roles.defaultModel(), roles.compactor(), value);
        };
        backend.applyRoles(updated);
        return CommandResult.ok("The " + label(which) + " is now " + (value == null ? "off" : value)
                + ". This applies to this session only.");
    }

    private static String label(String which) {
        return which.equals("model") ? "default model" : which + " model";
    }

    private CommandResult recall(String query) {
        if (query.isBlank()) {
            return CommandResult.fail("Usage: /recall <query>");
        }
        var hits = backend.recall(query);
        if (hits.isEmpty()) {
            return CommandResult.ok("No hits for: " + query);
        }
        return CommandResult.ok(String.join("\n", hits));
    }

    private String instructions() {
        var list = backend.instructions();
        if (list.isEmpty()) {
            return "No AGENTS.md files apply.";
        }
        var sb = new StringBuilder();
        for (var i : list) {
            sb.append(i.level().name().toLowerCase(java.util.Locale.ROOT)).append("  ").append(i.path());
            if (i.truncated()) {
                sb.append("  (truncated)");
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String skills() {
        var registry = backend.skills();
        var sb = new StringBuilder();
        if (registry.skills().isEmpty()) {
            sb.append("No skills.");
        }
        for (var s : registry.skills()) {
            sb.append(s.name()).append("  [").append(s.scope().name().toLowerCase(java.util.Locale.ROOT)).append("]  ")
                    .append(s.description()).append("\n  ").append(s.location()).append('\n');
        }
        appendDiagnostics(sb, registry.diagnostics());
        return sb.toString().stripTrailing();
    }

    private String commands() {
        var registry = backend.commands();
        var sb = new StringBuilder();
        if (registry.commands().isEmpty()) {
            sb.append("No custom commands.");
        }
        for (var c : registry.commands()) {
            sb.append('/').append(c.name());
            if (c.argumentHint() != null && !c.argumentHint().isBlank()) {
                sb.append(' ').append(c.argumentHint());
            }
            sb.append("  ").append(nullToEmpty(c.description()));
            if (c.model() != null && !c.model().isBlank()) {
                sb.append("  (model ").append(c.model()).append(')');
            }
            sb.append('\n');
        }
        appendDiagnostics(sb, registry.diagnostics());
        return sb.toString().stripTrailing();
    }

    private String agentTypes() {
        var registry = backend.agentTypes();
        var sb = new StringBuilder();
        if (registry.types().isEmpty()) {
            sb.append("No subagent definitions.");
        }
        for (var t : registry.types()) {
            sb.append(t.name()).append("  ").append(t.description());
            if (t.model() != null) {
                sb.append("  (model ").append(t.model()).append(')');
            }
            sb.append('\n');
        }
        appendDiagnostics(sb, registry.diagnostics());
        return sb.toString().stripTrailing();
    }

    private static void appendDiagnostics(StringBuilder sb, List<Diagnostic> diagnostics) {
        for (var d : diagnostics) {
            sb.append(format(d)).append('\n');
        }
    }

    private CommandResult trust(String arg) {
        if (arg.isBlank()) {
            if (backend.projectTrusted()) {
                return CommandResult.ok("This project is already trusted.");
            }
            backend.trustProject();
            return CommandResult.ok("This project is now trusted.");
        }
        if (arg.equals("revoke")) {
            backend.revokeTrust();
            return CommandResult.ok("The trust for this project is revoked.");
        }
        return CommandResult.fail("Usage: /trust [revoke]");
    }
}
