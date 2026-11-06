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
        assertEquals(55_084, report.budget(), "60% of the 91,808-token headroom");
        assertEquals(0, report.percent());
        assertEquals(91_808, report.headroom());
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
        assertEquals(55_084, usage.getLast().budget());

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
        assertTrue(text.contains("55,084 tokens (request at " + report.percent() + "%)"), text);
        assertTrue(text.contains("91,808 tokens (request at " + report.headroomPercent() + "%"), text);
        assertFalse(text.contains("WARNING"), text);
    }

    private static ContextReport report(int current, int request) {
        return new ContextReport("a1", 100, 200, 0, 0, 0, 0, 1_000, 2, current, request, 600, 1_000, 0, List.of());
    }

    @Test
    void belowTheSoftBudgetThereIsNoWarning() {
        var report = report(100, 500);
        assertEquals(83, report.percent());
        assertEquals(50, report.headroomPercent());
        assertFalse(report.overHeadroom());
        var text = report.render();
        assertTrue(text.contains("soft budget") && text.contains("hard headroom"), text);
        assertTrue(text.contains("request at 83%"), text);
        assertFalse(text.contains("WARNING"), text);
    }

    @Test
    void betweenTheSoftBudgetAndTheHeadroomTheReportShowsTheOverrunWithoutAWarning() {
        var report = report(400, 900);
        assertEquals(150, report.percent(), "the soft percent passes 100");
        assertEquals(90, report.headroomPercent());
        assertEquals(40, report.currentPercent());
        assertEquals(100, report.recentPercent());
        assertFalse(report.overHeadroom());
        assertFalse(report.render().contains("WARNING"), report.render());
    }

    @Test
    void overTheHeadroomTheReportWarnsAboutTheCurrentTurn() {
        var report = report(1_500, 2_000);
        assertTrue(report.overHeadroom());
        assertEquals(200, report.headroomPercent());
        var text = report.render();
        assertTrue(text.contains("WARNING: the request is 1,000 tokens over the hard headroom"), text);
        assertTrue(text.contains("current turn holds 1,500 tokens"), text);
        assertTrue(text.contains("/compact") && text.contains("recall"), text);
    }
}
