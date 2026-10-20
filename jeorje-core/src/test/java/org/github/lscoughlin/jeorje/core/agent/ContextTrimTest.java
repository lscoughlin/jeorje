package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.core.compaction.CompactionSettings;
import org.github.lscoughlin.jeorje.core.compaction.ContextCompactor;
import org.github.lscoughlin.jeorje.core.compaction.Pruner;
import org.github.lscoughlin.jeorje.core.compaction.TermStats;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A recent turn that does not fit the budget is pruned before it is dropped, and a drop leaves a trace. */
class ContextTrimTest {
    @TempDir Path dir;

    private static String big(String name, int chars) {
        var sb = new StringBuilder();
        for (var i = 0; sb.length() < chars; i++) {
            sb.append("line ").append(i).append(" of ").append(name).append(" with the class ").append(name)
                .append("Service and some more words\n");
        }
        return sb.toString();
    }

    private static ContextCompactor compactor() {
        var pruner = new Pruner(null, null, TermStats.UNIFORM, Pruner.Config.defaults());
        return new ContextCompactor(pruner, new CompactionSettings(1, 50, 80, 6, 12, 20, 16_000), new AgentId("a1"));
    }

    private static String first(LlmRequest request) {
        return request.messages().getFirst().content().getFirst().toString();
    }

    /** Turn 1 reads three 150,000 character results: about 112,000 tokens, over the budget of 60,000. */
    private Scripted script() {
        return new Scripted(calls("c1", "bigread", "{\"n\":\"One\"}"), calls("c2", "bigread", "{\"n\":\"Two\"}"),
            calls("c3", "bigread", "{\"n\":\"Three\"}"), text("Turn one is done."), text("Turn two."), text("Turn three."));
    }

    private Rig rig(Scripted llm, int recentMaxPercent, boolean withCompactor) {
        var bigread = tool("bigread", true, (in, ctx) -> ToolResultData.ok(big(in.get("n").asString(), 150_000)));
        var settings = Rig.settings(dir).withRecentMaxPercent(recentMaxPercent);
        var rig = new Rig(llm, policy(Decision.ALLOW), settings, bigread);
        if (withCompactor) rig.agent.withCompactor(compactor());
        return rig;
    }

    @Test
    void aBigRecentTurnIsPrunedBeforeItIsDropped() {
        var llm = script();
        var rig = rig(llm, 40, true);
        rig.agent.runTurn("read the three files");
        rig.agent.runTurn("what next?");
        var request = llm.requests.get(4);
        var preamble = first(request);
        assertFalse(preamble.contains("left the context"), "no drop: " + preamble.substring(0, Math.min(400, preamble.length())));
        assertTrue(preamble.contains("[Earlier context]") && preamble.contains("pruned L"), "pruned form with a hint");
        assertTrue(preamble.contains("recall:"), "and a recall hint");
        assertTrue(ContextBuilder.rawEstimate(request) <= 60_000, ContextBuilder.rawEstimate(request) + "");
        var trimmed = rig.events(AgentEvent.ContextTrimmed.class);
        assertFalse(trimmed.isEmpty());
        var last = trimmed.getLast();
        assertTrue(last.prunedChunks() > 0, "chunks were pruned");
        assertTrue(last.droppedTurns().isEmpty(), "no turn was dropped");
        assertFalse(last.overBudget());
    }

    @Test
    void whenPruningCannotHelpTheTurnLeavesWithANoteAndAnEvent() {
        var llm = script();
        var rig = rig(llm, 40, false);
        rig.agent.runTurn("read the three files");
        rig.agent.runTurn("what next?");
        var request = llm.requests.get(4);
        var preamble = first(request);
        assertTrue(preamble.contains("[Turn 1 of a1 left the context to fit the budget. Search it with recall "
            + "(the filter kind user finds the words of the user).]"), preamble.substring(0, Math.min(500, preamble.length())));
        assertFalse(preamble.contains("line 5 of One"), "the text of the turn is gone");
        var event = rig.events(AgentEvent.ContextTrimmed.class).stream()
            .filter(e -> !e.droppedTurns().isEmpty()).findFirst().orElseThrow();
        assertEquals(List.of(1), event.droppedTurns());
        assertEquals(0, event.prunedChunks());
    }

    @Test
    void theTurnThatRunsAloneCanStayOverTheBudgetWithOneNotice() {
        var llm = script();
        var rig = rig(llm, 40, true);
        rig.agent.runTurn("read the three files");
        var over = rig.events(AgentEvent.ContextTrimmed.class).stream().filter(AgentEvent.ContextTrimmed::overBudget).toList();
        assertEquals(1, over.size(), "one notice for the turn");
        assertTrue(over.getFirst().droppedTurns().isEmpty());
    }

    @Test
    void aTurnLeavesTheRecentWindowWhenItUsesTooMuchOfTheBudget() {
        var llm = script();
        var rig = rig(llm, 20, true);
        rig.agent.runTurn("read the three files");
        assertTrue(rig.agent.workingEntries().isEmpty(), "the last turn is always recent");
        rig.agent.runTurn("what next?");
        var turns = rig.agent.workingEntries().stream().map(e -> e.chunk().turn()).distinct().toList();
        assertEquals(List.of(1), turns, "turn 1 moved to the older entries after turn 2");
        assertTrue(rig.agent.workingEntries().stream().anyMatch(e -> e.level() > 0), "and it is pruned");
        rig.agent.runTurn("and then?");
        assertEquals(List.of(1), rig.agent.workingEntries().stream().map(e -> e.chunk().turn()).distinct().toList(),
            "a small turn stays recent");
        var request = llm.requests.getLast();
        assertTrue(first(request).contains("a1:1."), "turn 1 is in the earlier context");
    }

    @Test
    void noRequestOfASmallSessionChanges() {
        var llm = new Scripted(text("a"), text("b"), text("c"));
        var rig = new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir));
        for (var i = 0; i < 3; i++) rig.agent.runTurn("turn " + i);
        assertTrue(rig.events(AgentEvent.ContextTrimmed.class).isEmpty());
        var all = new ArrayList<String>();
        llm.requests.forEach(r -> all.add(r.messages().toString()));
        assertTrue(all.stream().noneMatch(s -> s.contains("left the context")));
    }
}
