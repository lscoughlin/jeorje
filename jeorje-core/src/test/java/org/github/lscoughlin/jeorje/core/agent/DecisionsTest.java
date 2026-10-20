package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.model.Turn;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DecisionsTest {
    @TempDir Path dir;

    private static final AgentId A = new AgentId("a1");

    private static Message ask(String id, String json) {
        return new Message(Role.ASSISTANT, List.of(new ToolUse(id, "ask_user", MAPPER.readTree(json))));
    }

    private static Message result(String id, String output, boolean error) {
        return new Message(Role.USER, List.of(new ToolResult(id, output, error)));
    }

    private static Turn turn(int n, Message... messages) {
        return new Turn(A, n, List.of(messages));
    }

    @Test
    void noDecisionGivesNoBlock() {
        assertNull(Decisions.build(List.of()));
        assertNull(Decisions.build(List.of(turn(1, Message.user("the task"), Message.assistant("ok")))));
    }

    @Test
    void anAnswerToAnOldQuestionStaysInTheBlock() {
        var t1 = turn(1, Message.user("make a plan"),
            ask("q1", "{\"question\":\"Which defaults?\",\"choices\":[\"PC\",\"Both\"]}"),
            result("q1", "User answer: Change the built-in defaults for every platform", false),
            Message.assistant("ok"));
        assertEquals("Decisions of the user:\n- Which defaults?: Change the built-in defaults for every platform",
            Decisions.build(List.of(t1)));
    }

    @Test
    void textsOfTheUserAreNotInTheBlock() {
        var t1 = turn(1, Message.user("make a plan"), Message.assistant("ok"));
        var t2 = turn(2, Message.user("use sqlite"), Message.assistant("ok"));
        assertNull(Decisions.build(List.of(t1, t2)));
    }

    @Test
    void structuredAnswersChatAndDeclinedResults() {
        var form = "{\"questions\":[{\"prompt\":\"Which database?\"},{\"prompt\":\"Which checks?\"}]}";
        var t1 = turn(1, Message.user("task"),
            ask("q1", form),
            result("q1", "User answers:\n- db \"Which database?\": sqlite\n- checks \"Which checks?\": unit; lint\n"
                + "and more", false),
            ask("q2", "{\"question\":\"Which format?\"}"),
            result("q2", "The user wants to talk about this before answering.\nNote from the user: what is YAML?\n"
                + "Open questions:\n- q1 \"Which format?\"\nAnswer in chat. Then call ask_user again if you still need an answer.", false),
            ask("q3", "{\"question\":\"Skip?\"}"),
            result("q3", "The user declined to answer.", false),
            ask("q4", "{\"question\":\"Fail?\"}"),
            result("q4", "cancelled", true));
        assertEquals("Decisions of the user:\n- Which database?: sqlite\n- Which checks?: unit; lint and more\n"
            + "- Which format?: (the user wanted to chat: what is YAML?)", Decisions.build(List.of(t1)));
    }

    @Test
    void aLongHistoryKeepsTheNewestLinesWithinTheLimit() {
        var turns = new ArrayList<Turn>();
        for (var i = 1; i <= 100; i++) {
            turns.add(turn(i, Message.user("first"), ask("q" + i, "{\"question\":\"Question number " + i + "?\"}"),
                result("q" + i, "User answer: " + "answer ".repeat(10) + i, false)));
        }
        var block = Decisions.build(turns);
        assertTrue(block.contains(Decisions.OLDER), block.substring(0, 200));
        assertTrue(block.endsWith("answer answer answer answer answer answer answer answer answer answer 100"), block);
        assertFalse(block.contains("Question number 1?"), "the oldest lines are cut");
        var bodyChars = block.lines().skip(2).mapToInt(l -> l.length() + 1).sum();
        assertTrue(bodyChars <= Decisions.MAX_CHARS, bodyChars + "");
    }

    @Test
    void theAgentPinsTheBlockInTheNextRequests() {
        var llm = new Scripted(calls("q1", "ask_user", "{\"question\":\"Which db?\"}"), text("Noted."),
            text("2"), text("3"));
        var ask = tool("ask_user", false, (in, ctx) -> ToolResultData.ok("User answer: sqlite"));
        var rig = new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir), ask);
        rig.agent.runTurn("plan it");
        rig.agent.runTurn("go on");
        var first = llm.requests.getLast().messages().getFirst().content().getFirst().toString();
        assertTrue(first.contains("Decisions of the user:\n- Which db?: sqlite"), first);
        assertTrue(first.indexOf("[Pinned items]") < first.indexOf("Decisions of the user:"), first);
        assertFalse(llm.requests.getFirst().toString().contains("Decisions of the user:"), "none before a decision");
    }
}
