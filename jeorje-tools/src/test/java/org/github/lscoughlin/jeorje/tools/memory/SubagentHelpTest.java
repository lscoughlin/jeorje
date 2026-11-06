package org.github.lscoughlin.jeorje.tools.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.subagent.TodoItem;
import org.junit.jupiter.api.Test;

class SubagentHelpTest {
    private static final AgentId SUB = new AgentId("sub-1");

    @Test
    void readsTheTodosOfTheSubagent() {
        var todo = new TodoTool();
        todo.replace("sub-1", List.of(new TodoTool.Item("Fix it", "in_progress")));
        var help = new SubagentHelp(todo, (a, q, c) -> "ok");
        assertEquals(List.of(new TodoItem("Fix it", "in_progress")), help.todos(SUB));
        assertTrue(help.todos(new AgentId("other")).isEmpty());
    }

    @Test
    void adviceWithTodoLinesReplacesTheTodos() {
        var todo = new TodoTool();
        todo.replace("sub-1", List.of(new TodoTool.Item("Old", "pending")));
        var help = new SubagentHelp(todo, (a, q, c) -> "Do this.\n- [x] Read log\n- [~] Fix parser\n- [~] Extra\n- [ ] Test");
        var advice = help.advise(SUB, "help", CancellationToken.none());
        assertTrue(advice.orElseThrow().startsWith("Do this."));
        assertEquals(List.of(new TodoItem("Read log", "completed"), new TodoItem("Fix parser", "in_progress"),
            new TodoItem("Extra", "pending"), new TodoItem("Test", "pending")), help.todos(SUB));
    }

    @Test
    void adviceWithoutTodoLinesKeepsTheTodos() {
        var todo = new TodoTool();
        todo.replace("sub-1", List.of(new TodoTool.Item("Old", "pending")));
        var help = new SubagentHelp(todo, (a, q, c) -> "Try the other file.");
        assertTrue(help.advise(SUB, "help", CancellationToken.none()).isPresent());
        assertEquals(List.of(new TodoItem("Old", "pending")), help.todos(SUB));
    }

    @Test
    void noAdviceWhenTheAdvisorIsOffOrFails() {
        var todo = new TodoTool();
        assertTrue(new SubagentHelp(todo, (a, q, c) -> null).advise(SUB, "x", CancellationToken.none()).isEmpty());
        assertTrue(new SubagentHelp(todo, (a, q, c) -> { throw new IllegalStateException("down"); })
            .advise(SUB, "x", CancellationToken.none()).isEmpty());
    }
}
