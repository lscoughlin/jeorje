package org.github.lscoughlin.jeorje.core.subagent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.model.Turn;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SummaryWriterTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static HistoryText history() {
        var input = MAPPER.createObjectNode().put("path", "src/Main.java");
        var turn = new Turn(new AgentId("a"), 1, List.of(
            Message.user("Fix the NullPointerException in Main.java"),
            new Message(Role.ASSISTANT, List.of(new Text("I will edit it."), new ToolUse("c1", "edit", input))),
            new Message(Role.USER, List.of(new ToolResult("c1", "edited src/Main.java", false))),
            Message.assistant("Fixed the NullPointerException in Main.java.")));
        return HistoryText.of(List.of(turn));
    }

    @Test
    void historyCollectsChangedFilesAndTheLastAssistantText() {
        var history = history();
        assertEquals(List.of("src/Main.java"), history.changedFiles());
        assertEquals("Fixed the NullPointerException in Main.java.", history.lastAssistantText());
        assertTrue(history.contains("nullpointerexception"));
        assertFalse(history.contains("Nonexistent"));
    }

    @Test
    void fallbackPassesTheCheckForEveryOutcome() {
        var history = history();
        for (var outcome : List.of("completed", "failed", "cancelled")) {
            var reason = outcome.equals("completed") ? null : "stopped";
            var text = SummaryWriter.fallback(new SummaryWriter.Request("w", outcome, reason, null, history));
            assertEquals(List.of(), SummaryCheck.validate(text, outcome, history), text);
        }
    }

    @Test
    void fallbackStaysShortForLongInput() {
        var long_ = "word ".repeat(2_000);
        var turn = new Turn(new AgentId("a"), 1, List.of(Message.user("Task about Widget.java"),
            Message.assistant(long_)));
        var history = HistoryText.of(List.of(turn));
        var text = SummaryWriter.fallback(new SummaryWriter.Request("w", "failed", long_, long_, history));
        assertTrue(SummaryCheck.words(text) <= 300, "words: " + SummaryCheck.words(text));
        assertEquals(List.of(), SummaryCheck.validate(text, "failed", history));
    }

    @Test
    void parserAcceptsMarkdownLabels() {
        var parts = SummaryCheck.parts("**Outcome:** completed\n- Done: yes\nOpen issues: none\nmore text");
        assertEquals("completed", parts.get("Outcome"));
        assertEquals("yes", parts.get("Done"));
        assertEquals("none more text", parts.get("Open issues"));
    }

    @Test
    void cleanRemovesACodeFence() {
        assertEquals("Outcome: completed", SummaryCheck.clean("```text\nOutcome: completed\n```"));
    }

    @Test
    void tooManyKeywordsAreRejected() {
        var many = String.join(", ", java.util.Collections.nCopies(13, "Main.java"));
        var text = """
            Outcome: completed
            Done: x
            Files: x
            Branch: x
            Decisions: x
            Verification: x
            Open issues: x
            Keywords: %s""".formatted(many);
        var problems = SummaryCheck.validate(text, "completed", history());
        assertTrue(problems.stream().anyMatch(p -> p.contains("Keywords must have 3 to 12")), problems.toString());
    }
}
