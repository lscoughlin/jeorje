package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextReportTest {
    @TempDir Path dir;

    @Test
    void beforeTheFirstCallThereAreNoNumbers() {
        var rig = new Rig(new Scripted(text("a")), policy(Decision.ALLOW), Rig.settings(dir));
        var report = rig.agent.contextReport();
        assertEquals(0, report.request());
        assertEquals(60_000, report.budget());
        assertEquals(0, report.percent());
        assertTrue(report.render().contains("request"));
    }

    @Test
    void theReportAndTheEventHaveTheNumbersOfTheLastCall() {
        var ping = tool("ping", true, (in, ctx) -> ToolResultData.ok("pong ".repeat(2000)));
        var llm = new Scripted(calls("c1", "ping", "{}"), text("one"), text("two"));
        var rig = new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir), ping);
        rig.agent.runTurn("first");
        rig.agent.runTurn("second");

        var usage = rig.events(AgentEvent.ContextUsage.class);
        assertEquals(3, usage.size(), "one event for each model call");
        assertTrue(usage.get(1).tokens() > usage.get(0).tokens(), "the second call has the tool result");
        assertEquals(60_000, usage.getLast().budget());

        var report = rig.agent.contextReport();
        assertEquals(usage.getLast().tokens(), report.request());
        assertTrue(report.system() > 0 && report.tools() > 0, report.toString());
        assertEquals(1, report.recentTurns());
        assertTrue(report.recent() > 2000, "the first turn has the 10,000 character result");
        assertEquals(List.of(), report.droppedTurns());
        var text = report.render();
        assertTrue(text.startsWith("Context of a1 at the last model call (estimates):\n"), text);
        for (var row : List.of("system prompt", "tool definitions", "pinned items", "earlier context",
            "recent turns", "current turn", "request")) {
            assertTrue(text.contains(row), row + "\n" + text);
        }
        assertTrue(text.contains("1 turn)"), text);
        assertTrue(text.contains("Results moved out of a running turn: 0"), text);
        assertTrue(text.endsWith("Turns that left the last request: none"), text);
        assertTrue(text.contains("of 60,000 tokens (" + report.percent() + "%)"), text);
    }
}
