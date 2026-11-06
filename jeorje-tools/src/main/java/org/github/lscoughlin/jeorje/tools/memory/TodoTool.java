package org.github.lscoughlin.jeorje.tools.memory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.Required;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;

/**
 * The {@code todo} tool. It keeps the task list of each agent. Each call replaces the whole list
 * and returns the new list. The context classifier pins the result of this tool, so the list stays
 * in the working context. The runtime can read the list with {@link #list}.
 */
public final class TodoTool extends RecordTool<TodoTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "todo";
    private static final Set<String> STATUSES = Set.of("pending", "in_progress", "completed");

    /** One task. */
    public record Item(
            @Required @Describe("What to do.") String content,
            @Required @Describe("pending, in_progress, or completed.") String status) {}

    /** The input of {@code todo}. */
    public record Input(
            @Required @Describe("The full task list. It replaces the old list.") List<Item> items) {}

    private final Map<String, List<Item>> lists = new ConcurrentHashMap<>();
    private volatile Consumer<AgentId> progress = agent -> { };

    /**
     * Sets the listener for progress. The tool calls it with the agent when a call of that agent completes a task
     * that was not completed before. The CLI connects it to {@code noteProgress} of the subagent runtimes.
     */
    public TodoTool onProgress(Consumer<AgentId> listener) {
        this.progress = listener == null ? agent -> { } : listener;
        return this;
    }

    public TodoTool() {
        super(NAME, "Keep your task list. Each call replaces the whole list. Keep at most one task "
                + "in_progress. The list stays pinned in your context.", Input.class, false);
    }

    /** The current list of an agent. It is empty before the first call. */
    public List<Item> list(String agentId) { return lists.getOrDefault(agentId, List.of()); }

    /** Replaces the list of an agent. The advisor uses this to update the todos of a subagent. */
    public void replace(String agentId, List<Item> items) { lists.put(agentId, List.copyOf(items)); }

    /** Renders a list as text. */
    public static String render(List<Item> items) {
        if (items.isEmpty()) return "Todo list: empty.";
        var out = new StringBuilder("Todo list:\n");
        var n = 1;
        for (var item : items) {
            var mark = switch (item.status()) {
                case "completed" -> "[x]";
                case "in_progress" -> "[~]";
                default -> "[ ]";
            };
            out.append(n++).append(". ").append(mark).append(' ').append(item.content()).append('\n');
        }
        return out.toString();
    }

    /** True if the new list has a completed task that the old list did not complete. */
    static boolean completedNow(List<Item> before, List<Item> after) {
        var done = new java.util.HashMap<String, Integer>();
        for (var item : before) if (item.status().equals("completed")) done.merge(item.content(), 1, Integer::sum);
        for (var item : after) {
            if (!item.status().equals("completed")) continue;
            if (done.merge(item.content(), -1, Integer::sum) < 0) return true;
        }
        return false;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) {
        var items = input.items() == null ? List.<Item>of() : input.items();
        var inProgress = 0;
        for (var item : items) {
            if (item.content() == null || item.content().isBlank()) return ToolResultData.error("A task has no content.");
            if (item.status() == null || !STATUSES.contains(item.status())) {
                return ToolResultData.error("Unknown status '" + item.status() + "'. Use pending, in_progress, or completed.");
            }
            if (item.status().equals("in_progress")) inProgress++;
        }
        if (inProgress > 1) return ToolResultData.error("More than one task is in_progress.");
        var copy = List.copyOf(items);
        var before = lists.put(context.agent().value(), copy);
        if (completedNow(before == null ? List.of() : before, copy)) {
            try {
                progress.accept(context.agent());
            } catch (RuntimeException e) {
                // A failed listener must not fail the tool call.
            }
        }
        return ToolResultData.ok(render(copy));
    }
}
