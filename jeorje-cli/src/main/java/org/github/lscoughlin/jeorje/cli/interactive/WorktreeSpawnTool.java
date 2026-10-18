package org.github.lscoughlin.jeorje.cli.interactive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.github.lscoughlin.jeorje.core.instructions.AgentType;
import org.github.lscoughlin.jeorje.core.instructions.AgentTypeRegistry;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.subagent.Completion;
import org.github.lscoughlin.jeorje.core.subagent.SpawnException;
import org.github.lscoughlin.jeorje.core.subagent.SpawnMode;
import org.github.lscoughlin.jeorje.core.subagent.SpawnRequest;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.core.worktree.WorktreeException;
import org.github.lscoughlin.jeorje.core.worktree.WorktreeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The {@code spawn_agent} tool with worktree isolation. A call that needs a worktree starts the subagent with the
 * working directory of the future worktree, makes the worktree for the new agent id, and tells the runtime the
 * branch. Other calls go to the wrapped tool.
 *
 * <p>The subagent starts before the worktree exists. The first tool call of a subagent comes after a model
 * round trip, so the worktree is ready by then. The orchestrator of {@code /implement} works the same way.
 */
final class WorktreeSpawnTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(WorktreeSpawnTool.class);
    private static final String GROUP = "session";

    private final Tool delegate;
    private final SubagentRuntime runtime;
    private final WorktreeManager worktrees;
    private final Supplier<AgentTypeRegistry> types;

    WorktreeSpawnTool(Tool delegate, SubagentRuntime runtime, WorktreeManager worktrees,
                      Supplier<AgentTypeRegistry> types) {
        this.delegate = delegate;
        this.runtime = runtime;
        this.worktrees = worktrees;
        this.types = types;
    }

    /** Replaces the {@code spawn_agent} tool in a tool list. Other tools stay. */
    static List<Tool> wrap(List<Tool> tools, SubagentRuntime runtime, WorktreeManager worktrees,
                           Supplier<AgentTypeRegistry> types) {
        if (worktrees == null) return tools;
        var out = new ArrayList<Tool>();
        for (var tool : tools) {
            out.add(tool.definition().name().equals(SubagentRuntime.SPAWN_TOOL)
                    ? new WorktreeSpawnTool(tool, runtime, worktrees, types) : tool);
        }
        return out;
    }

    @Override
    public ToolDefinition definition() { return delegate.definition(); }

    @Override
    public boolean readOnly() { return delegate.readOnly(); }

    @Override
    public ToolResultData execute(JsonNode input, ToolContext context) throws Exception {
        var name = text(input, "name");
        var task = text(input, "task");
        if (name == null || task == null || text(input, "peer") != null || text(input, "capability") != null
                || text(input, "workdir") != null) {
            return delegate.execute(input, context);
        }
        var type = text(input, "type");
        var isolation = text(input, "isolation");
        if (isolation == null && type != null) {
            isolation = types.get().find(type).map(AgentType::isolation).orElse(null);
        }
        var mode = isolation == null ? worktrees.config().isolation()
                : isolation.equals("worktree") ? "always" : "never";
        if (mode.equals("never")) return delegate.execute(input, context);
        var reason = cannotIsolate(context.workingDirectory());
        if (reason != null) {
            if (mode.equals("always")) return ToolResultData.error("spawn_agent failed: no worktree: " + reason);
            return delegate.execute(input, context);
        }
        SpawnMode spawnMode;
        try {
            spawnMode = SpawnMode.parse(text(input, "mode"));
        } catch (SpawnException e) {
            return ToolResultData.error(e.getMessage());
        }
        List<String> tools = null;
        if (input.get("tools") != null && input.get("tools").isArray()) {
            tools = new ArrayList<>();
            for (var t : input.get("tools")) tools.add(t.asString());
        }
        var wtName = freeName(context.workingDirectory(), slug(name));
        var predicted = worktrees.projectRoot().resolve(".jeorje").resolve("worktrees").resolve(GROUP).resolve(wtName);
        var request = new SpawnRequest(name, task, tools, type, text(input, "model"), "worktree", spawnMode,
                predicted, null);
        AgentId id;
        try {
            id = runtime.spawn(context.agent(), request);
        } catch (SpawnException e) {
            return ToolResultData.error("spawn_agent failed: " + e.getMessage());
        }
        try {
            var created = worktrees.create(new WorktreeManager.CreateRequest(id, context.agent(), wtName, GROUP, task,
                    context.workingDirectory(), spawnMode, "worktree", false, null));
            var w = created.orElseThrow(() -> new WorktreeException("The worktree was not made."));
            if (!w.path.toAbsolutePath().normalize().equals(predicted.toAbsolutePath().normalize())) {
                throw new WorktreeException("The worktree is at " + w.path + ", not at " + predicted + ".");
            }
            runtime.setBranch(id, w.branch);
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "Cannot make the worktree of " + name + ": " + e.getMessage());
            runtime.cancel(id.value());
            return ToolResultData.error("spawn_agent failed: cannot make the worktree: " + e.getMessage());
        }
        var dropped = runtime.droppedTools(id);
        var note = dropped.isEmpty() ? "" : "\nTools that you do not have were not given: " + String.join(", ", dropped);
        if (spawnMode == SpawnMode.BACKGROUND) {
            return ToolResultData.ok("Started subagent " + name + " with agent id " + id
                    + ". Its completion summary comes later as an input event." + note);
        }
        Completion completion = runtime.await(id, context.cancel());
        var out = "Subagent " + completion.name() + " (" + completion.agent() + ") ended with the outcome "
                + completion.outcome().name().toLowerCase(Locale.ROOT) + ".\n" + completion.summary() + note;
        return completion.outcome() == Completion.Outcome.COMPLETED ? ToolResultData.ok(out)
                : ToolResultData.error(out);
    }

    /** Null when the directory can have a worktree, else the reason. */
    private String cannotIsolate(Path dir) {
        var git = worktrees.git();
        var inside = git.run(dir, "rev-parse", "--is-inside-work-tree");
        if (!inside.ok() || !inside.trimmed().equals("true")) return "the directory is not inside a git work tree";
        if (!git.run(dir, "symbolic-ref", "--short", "-q", "HEAD").ok()) return "HEAD is detached";
        return null;
    }

    /** A worktree name that no directory and no branch uses yet. */
    private String freeName(Path dir, String base) {
        var prefix = worktrees.config().branchPrefix();
        if (prefix == null || prefix.isBlank()) prefix = "jeorje";
        var root = worktrees.projectRoot().resolve(".jeorje").resolve("worktrees").resolve(GROUP);
        for (var suffix = 1; ; suffix++) {
            var tail = suffix == 1 ? base : base + "-" + suffix;
            var taken = Files.exists(root.resolve(tail)) || worktrees.git()
                    .run(dir, "show-ref", "--verify", "--quiet", "refs/heads/" + prefix + "/" + GROUP + "/" + tail).ok();
            if (!taken) return tail;
        }
    }

    static String slug(String text) {
        var s = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^[-.]+|[-.]+$", "");
        s = s.replace("..", ".").replaceAll("\\.lock$", "");
        return s.isEmpty() ? "agent" : s.length() > 60 ? s.substring(0, 60) : s;
    }

    private static String text(JsonNode input, String field) {
        if (input == null || input.get(field) == null || input.get(field).isNull()) return null;
        var value = input.get(field).asString();
        return value == null || value.isBlank() ? null : value;
    }
}
