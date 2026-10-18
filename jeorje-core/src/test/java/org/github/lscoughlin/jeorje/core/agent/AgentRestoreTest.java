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
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.session.SessionManifest;
import org.github.lscoughlin.jeorje.core.session.TranscriptReader;
import org.github.lscoughlin.jeorje.core.session.TranscriptWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentRestoreTest {
    @TempDir Path dir;

    private static String bigText() {
        var sb = new StringBuilder();
        for (var i = 0; i < 300; i++) sb.append("line ").append(i).append(" of the big output with some words\n");
        return sb.toString();
    }

    private static AgentSettings settings(Path dir) {
        var d = AgentSettings.defaults("s1", dir, 100_000);
        return new AgentSettings(d.sessionId(), dir, 100_000, 60, 8192, 2, 100, d.retry(), null, "", d.sleeper());
    }

    private static ContextCompactor compactor() {
        var pruner = new Pruner(null, null, TermStats.UNIFORM, Pruner.Config.defaults());
        return new ContextCompactor(pruner, new CompactionSettings(1, 50, 80, 2, 12, 20, 16_000), new AgentId("a1"));
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

    @Test
    void aSessionWithTurnsPinsAndPruningResumesWithTheSameRequest() {
        var firstScript = script(5);
        var first = new Rig(firstScript, policy(Decision.ALLOW), settings(dir), big());
        first.agent.withCompactor(compactor());
        first.agent.pin("Always use the offline profile.");
        for (var t = 1; t <= 4; t++) first.agent.runTurn("look " + t);
        assertTrue(first.agent.workingEntries().stream().anyMatch(e -> e.level() > 0), "the run pruned chunks");

        // Persist: the manifest on disk and the transcript file.
        var manifestFile = dir.resolve("manifest.json");
        var manifest = first.agent.recordInto(SessionManifest.create("s1", CLOCK.instant(), null));
        manifest.write(manifestFile);
        assertFalse(manifest.pruning().isEmpty());
        assertEquals(1, manifest.pinned().size());
        var transcriptFile = dir.resolve("transcript.jsonl");
        try (var writer = new TranscriptWriter(transcriptFile)) {
            first.transcript.forEach(turn -> writer.writeTurn(turn, CLOCK.instant()));
        }

        // The next request of the first run.
        first.agent.runTurn("look 5");
        var expected = firstScript.requests.get(firstScript.requests.size() - 2);

        // Resume in a new agent with the same id.
        var secondScript = new Scripted(calls("c5", "big", "{}"), text("Done 5"));
        var second = new Rig(secondScript, policy(Decision.ALLOW), settings(dir), big());
        second.agent.withCompactor(compactor());
        second.agent.restore(SessionManifest.read(manifestFile), TranscriptReader.read(transcriptFile));
        assertEquals(4, second.agent.turns().size());
        assertEquals(first.agent.pinnedTexts().subList(0, 1), second.agent.pinnedTexts());
        second.agent.runTurn("look 5");

        var actual = secondScript.requests.getFirst();
        assertEquals(expected, actual, "the resumed request equals the request of the first run");
        assertTrue(actual.toString().contains("pruned L"), "the request holds pruned chunks");
        assertTrue(actual.toString().contains("Always use the offline profile."));

        // The scripted model continued: turn 5 exists, with the next turn number.
        assertEquals(5, second.agent.turns().size());
        assertEquals(5, second.agent.turns().getLast().number());
        assertTrue(second.store.chunks.stream().anyMatch(c -> c.chunkId().equals("a1:5.0")));
        assertEquals(first.agent.workingEntries().size(), second.agent.workingEntries().size());
    }

    @Test
    void turnsOfOtherAgentsAreIgnored() {
        var script = script(1);
        var rig = new Rig(script, policy(Decision.ALLOW), settings(dir), big());
        rig.agent.runTurn("look 1");
        var other = new org.github.lscoughlin.jeorje.core.model.Turn(new AgentId("sub-1"), 1,
            rig.transcript.getFirst().messages());
        var restored = new Rig(new Scripted(text("ok")), policy(Decision.ALLOW), settings(dir), big());
        restored.agent.restore(null, List.of(
            new TranscriptReader.TimedTurn(other, CLOCK.instant()),
            new TranscriptReader.TimedTurn(rig.transcript.getFirst(), CLOCK.instant())));
        assertEquals(1, restored.agent.turns().size());
        assertEquals("a1", restored.agent.turns().getFirst().agent().value());
    }
}
