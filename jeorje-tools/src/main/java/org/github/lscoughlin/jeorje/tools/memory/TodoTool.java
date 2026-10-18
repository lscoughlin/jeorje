package org.github.lscoughlin.jeorje.tools.memory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    public TodoTool() {
        super(NAME, "Keep your task list. Each call replaces the whole list. Keep at most one task "
                + "in_progress. The list stays pinned in your context.", Input.class, false);
    }

    /** The current list of an agent. It is empty before the first call. */
    public List<Item> list(String agentId) { return lists.getOrDefault(agentId, List.of()); }

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
        lists.put(context.agent().value(), copy);
        return ToolResultData.ok(render(copy));
    }
}
