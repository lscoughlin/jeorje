package org.github.lscoughlin.jeorje.tools.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.subagent.AdvisorLink;
import org.github.lscoughlin.jeorje.core.subagent.TodoItem;

/**
 * The {@link AdvisorLink} for the turn watch of the subagent runtime. It reads the todos from the {@link TodoTool}
 * of the subagent. It asks the advisor through {@link AdvisorTool#advise}, the same path as the {@code advisor}
 * tool. When the advice has todo lines ({@code - [ ] text}, {@code - [~] text}, {@code - [x] text}), the lines
 * replace the todo list of the subagent.
 */
public final class SubagentHelp implements AdvisorLink {
    /** Asks the advisor. It returns null when the advisor is off. */
    @FunctionalInterface
    public interface Advisor {
        String advise(AgentId agent, String question, CancellationToken cancel) throws Exception;
    }

    private static final Pattern TODO_LINE = Pattern.compile("^\\s*[-*]\\s*\\[([ x~])]\\s+(\\S.*)$");

    private final TodoTool todos;
    private final Advisor advisor;

    /**
     * @param todos the todo tool that the subagents use
     * @param advisor asks the advisor
     */
    public SubagentHelp(TodoTool todos, Advisor advisor) {
        this.todos = todos;
        this.advisor = advisor;
    }

    @Override
    public List<TodoItem> todos(AgentId agent) {
        return todos.list(agent.value()).stream().map(i -> new TodoItem(i.content(), i.status())).toList();
    }

    @Override
    public Optional<String> advise(AgentId agent, String request, CancellationToken cancel) {
        String advice;
        try {
            advice = advisor.advise(agent, request, cancel);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
        if (advice == null || advice.isBlank()) return Optional.empty();
        var items = parse(advice);
        if (!items.isEmpty()) todos.replace(agent.value(), items);
        return Optional.of(advice);
    }

    /** The todo lines of a text, or an empty list. At most one task is {@code in_progress}. */
    static List<TodoTool.Item> parse(String text) {
        var items = new ArrayList<TodoTool.Item>();
        var inProgress = 0;
        for (var line : text.split("\\R")) {
            var m = TODO_LINE.matcher(line);
            if (!m.matches()) continue;
            var status = switch (m.group(1)) {
                case "x" -> "completed";
                case "~" -> "in_progress";
                default -> "pending";
            };
            if (status.equals("in_progress") && ++inProgress > 1) status = "pending";
            items.add(new TodoTool.Item(m.group(2).strip(), status));
        }
        return items;
    }
}
