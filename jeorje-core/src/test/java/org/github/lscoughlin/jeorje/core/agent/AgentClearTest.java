package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.compaction.CompactionSettings;
import org.github.lscoughlin.jeorje.core.compaction.ContextCompactor;
import org.github.lscoughlin.jeorje.core.compaction.Pruner;
import org.github.lscoughlin.jeorje.core.compaction.TermStats;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.session.TranscriptReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentClearTest {
    @TempDir Path dir;

    private static String bigText() {
        var sb = new StringBuilder();
        for (var i = 0; i < 300; i++) sb.append("line ").append(i).append(" of the big output with some words\n");
        return sb.toString();
    }

    private static AgentSettings settings(Path dir) {
        var d = AgentSettings.defaults("s1", dir, 100_000);
        return new AgentSettings(d.sessionId(), dir, 100_000, 60, 8192, 1, 100, d.retry(), null, "", d.sleeper());
    }

    private static ContextCompactor compactor() {
        var pruner = new Pruner(null, null, TermStats.UNIFORM, Pruner.Config.defaults());
        return new ContextCompactor(pruner, new CompactionSettings(1, 50, 80, 1, 12, 20, 16_000), new AgentId("a1"));
    }

    private static Tool big() {
        var text = bigText();
        return tool("big", true, (in, ctx) -> ToolResultData.ok(text));
    }

    private static Scripted script(int turns) {
        var steps = new ArrayList<Step>();
        for (var t = 1; t <= turns; t++) {
            steps.add(calls("c" + t, "big", "{}"));
            steps.add(text("Done " + t));
        }
        return new Scripted(steps.toArray(new Step[0]));
    }

    /** The texts of the user messages of a request, in order. */
    private static List<String> userTexts(LlmRequest request) {
        var out = new ArrayList<String>();
        for (var message : request.messages()) {
            if (message.role() != Role.USER) continue;
            for (var c : message.content()) {
                if (c instanceof Text t) out.add(t.text());
            }
        }
        return out;
    }

    @Test
    void clearForgetsTheTurnsThePinsAndThePrunedContext() {
        var script = script(4);
        var rig = new Rig(script, policy(Decision.ALLOW), settings(dir), big());
        rig.agent.withCompactor(compactor());
        rig.agent.pin("Always use the offline profile.");
        for (var t = 1; t <= 3; t++) rig.agent.runTurn("look " + t);
        assertFalse(rig.agent.pinnedTexts().isEmpty());
        assertFalse(rig.agent.workingEntries().isEmpty());

        assertEquals(3, rig.agent.clear());
        assertTrue(rig.agent.turns().isEmpty());
        assertTrue(rig.agent.pinnedTexts().isEmpty());
        assertTrue(rig.agent.workingEntries().isEmpty());

        rig.agent.runTurn("start again");
        var request = script.requests.getLast();
        assertEquals(List.of("start again"), userTexts(request), "the request holds only the new input");
        assertFalse(request.toString().contains("Always use the offline profile."), "the pins are gone");
        assertFalse(request.toString().contains("[Earlier context]"), "the pruned context is gone");

        // The turn counter continues, so the earlier turns keep their numbers on disk.
        assertEquals(1, rig.agent.turns().size());
        assertEquals(4, rig.agent.turns().getFirst().number());
        assertEquals(4, rig.transcript.size());
    }

    @Test
    void clearWaitsForARunningTurnAndThenClears() throws Exception {
        var emitted = new CountDownLatch(1);
        var hang = new Step.HangAfter(List.of(new LlmEvent.TextDelta("hi")), emitted);
        var script = new Scripted(hang, text("done"));
        var rig = new Rig(script, policy(Decision.ALLOW), settings(dir), big());
        var thread = Thread.ofVirtual().start(() -> rig.agent.runTurn("first"));
        var cleared = new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);
        var clearThread = Thread.ofPlatform().start(() -> {
            try {
                cleared.set(rig.agent.clear(30_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(emitted.await(10, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertEquals(Integer.MIN_VALUE, cleared.get(), "the clear waits for the turn");
            assertEquals(-1, rig.agent.clear(0), "a timeout keeps the clear out");
        } finally {
            rig.agent.cancel();
        }
        thread.join(10_000);
        assertFalse(thread.isAlive());
        clearThread.join(10_000);
        assertFalse(clearThread.isAlive());
        assertEquals(1, cleared.get(), "the clear ran after the turn");
        assertTrue(rig.agent.turns().isEmpty());
    }

    @Test
    void aRestoreBringsTheTurnsBackAfterAClear() {
        var first = new Rig(script(2), policy(Decision.ALLOW), settings(dir), big());
        first.agent.runTurn("look 1");
        first.agent.runTurn("look 2");
        first.agent.clear();
        assertTrue(first.agent.turns().isEmpty());

        var timed = first.transcript.stream()
                .map(t -> new TranscriptReader.TimedTurn(t, CLOCK.instant())).toList();
        var second = new Rig(new Scripted(text("ok")), policy(Decision.ALLOW), settings(dir), big());
        second.agent.restore(null, timed);
        assertEquals(2, second.agent.turns().size());
        assertEquals(List.of("look 1", "look 2"), second.agent.turns().stream()
                .flatMap(t -> userTexts(new LlmRequest("", t.messages(), List.of(), 100)).stream()).toList());
    }
}
