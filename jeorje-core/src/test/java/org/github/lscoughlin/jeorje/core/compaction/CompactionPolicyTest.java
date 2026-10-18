package org.github.lscoughlin.jeorje.core.compaction;

import static org.github.lscoughlin.jeorje.core.compaction.CompactionTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.github.lscoughlin.jeorje.core.config.ConfigException;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.session.SessionManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompactionPolicyTest {
    private static final AgentId AGENT = new AgentId("a");

    private static ContextCompactor compactor(int budget, ScriptedLlm llm) {
        var pruner = new Pruner(llm, null, TermStats.UNIFORM, Pruner.Config.defaults());
        return new ContextCompactor(pruner, CompactionSettings.defaults(budget), AGENT);
    }

    private static ContextEntry entry(int turn, int part, String kind, String tool, Protection p, int lines) {
        return ContextEntry.of(chunk("a", turn, part, kind, tool, body("WidgetService", lines)), p);
    }

    @Test
    void belowTheSoftTriggerNothingChanges() {
        var entries = List.of(entry(1, 0, "tool_result", "read", Protection.AGGRESSIVE, 3));
        var out = compactor(100_000, null).compact(entries, 30, 0, Set.of());
        assertFalse(out.soft());
        assertNull(out.event());
        assertEquals(entries, out.entries());
    }

    @Test
    void softTriggerRaisesOldChunksButNotProtectedOrRecentOnes() {
        var entries = new ArrayList<ContextEntry>();
        entries.add(entry(1, 0, "user", null, Protection.PROTECTED, 40));
        entries.add(entry(2, 0, "tool_result", "read", Protection.AGGRESSIVE, 40));
        entries.add(entry(3, 0, "assistant", null, Protection.CAREFUL, 40));
        entries.add(entry(13, 0, "tool_result", "read", Protection.AGGRESSIVE, 40));
        var total = entries.stream().mapToInt(ContextEntry::tokens).sum();
        var out = compactor(total * 3 / 2, null).compact(entries, 15, 0, Set.of());
        assertTrue(out.soft());
        assertFalse(out.hard());
        var e = out.entries();
        assertEquals(0, e.get(0).level());
        assertEquals(entries.get(0), e.get(0));
        assertEquals(2, e.get(1).level());
        assertEquals(1, e.get(2).level());
        assertEquals(0, e.get(3).level());
        assertEquals(2, out.chunksPruned());
        assertTrue(out.tokensFreed() > 0);
        assertEquals(new org.github.lscoughlin.jeorje.core.model.AgentEvent.Compacted(AGENT, 2, out.tokensFreed()), out.event());
    }

    @Test
    void veryOldAggressiveChunksGoToLevelThree() {
        var entries = List.of(entry(1, 0, "tool_result", "read", Protection.AGGRESSIVE, 40),
            entry(2, 0, "tool_result", "read", Protection.AGGRESSIVE, 40));
        var total = entries.stream().mapToInt(ContextEntry::tokens).sum();
        var out = compactor(total, null).compact(entries, 40, 0, Set.of());
        assertTrue(out.entries().stream().allMatch(x -> x.level() == 3));
    }

    @Test
    void hardTriggerRaisesCarefulChunksUntilUnderTheSoftLimitAndStopsAtLevelTwo() {
        var entries = new ArrayList<ContextEntry>();
        for (var i = 0; i < 12; i++) entries.add(entry(i + 1, 0, "assistant", null, Protection.CAREFUL, 30));
        var total = entries.stream().mapToInt(ContextEntry::tokens).sum();
        var budget = total * 10 / 12;
        var out = compactor(budget, null).compact(entries, 40, 0, Set.of());
        assertTrue(out.hard());
        assertTrue(out.entries().stream().allMatch(x -> x.level() <= 2));
        var after = out.entries().stream().mapToInt(ContextEntry::tokens).sum();
        assertTrue(after <= budget / 2, "after=" + after + " budget=" + budget);
        assertTrue(out.entries().stream().anyMatch(x -> x.level() == 2));
    }

    @Test
    void hardTriggerUsesTheOverheadOfTheNextRequest() {
        var entries = List.of(entry(1, 0, "assistant", null, Protection.CAREFUL, 30));
        var t = entries.getFirst().tokens();
        var low = compactor(t * 3, null).compact(entries, 40, 0, Set.of());
        assertFalse(low.hard());
        var high = compactor(t * 3, null).compact(entries, 40, t * 2, Set.of());
        assertTrue(high.hard());
    }

    @Test
    void evictionOrderIsLargeResultsThenLogsThenOtherOutputThenCarefulOldestFirst() {
        var careful1 = entry(1, 0, "assistant", null, Protection.CAREFUL, 10);
        var careful2 = entry(2, 0, "assistant", null, Protection.CAREFUL, 10);
        var other = entry(3, 0, "tool_result", "grep", Protection.AGGRESSIVE, 5);
        var log = entry(4, 0, "tool_result", "bash", Protection.AGGRESSIVE, 5);
        var large = ContextEntry.of(chunk("a", 5, 0, "tool_result", "read", "x".repeat(9000)), Protection.AGGRESSIVE);
        var protectedOne = entry(6, 0, "user", null, Protection.PROTECTED, 5);
        var recent = entry(39, 0, "tool_result", "read", Protection.AGGRESSIVE, 5);
        var list = List.of(careful1, careful2, other, log, large, protectedOne, recent);
        var order = compactor(1000, null).evictionOrder(list, 40);
        assertEquals(List.of(4, 3, 2, 0, 1), order);
    }

    @Test
    void recallResultsBecomeStubsWithSourceIds() {
        var recall = ContextEntry.of(chunk("a", 2, 0, "tool_call", "recall", "RECALL query=" + "q ".repeat(300)
            + " returned=a:1.0,a:4.2"), Protection.AGGRESSIVE);
        var filler = entry(3, 0, "tool_result", "read", Protection.AGGRESSIVE, 40);
        var total = recall.tokens() + filler.tokens();
        var out = compactor(total, null).compact(List.of(recall, filler), 30, 0, Set.of());
        var r = out.entries().getFirst();
        assertEquals(3, r.level());
        assertTrue(r.render().contains("a:1.0") && r.render().contains("a:4.2"));
        assertTrue(r.tokens() < recall.tokens());
    }

    @Test
    void pruningTableRoundTrips() {
        var entries = new ArrayList<ContextEntry>();
        entries.add(entry(1, 0, "tool_result", "read", Protection.AGGRESSIVE, 40));
        entries.add(entry(2, 0, "assistant", null, Protection.CAREFUL, 40));
        entries.add(entry(3, 0, "user", null, Protection.PROTECTED, 4));
        var total = entries.stream().mapToInt(ContextEntry::tokens).sum();
        var out = compactor(total / 2, null).compact(entries, 30, 0, Set.of());
        var manifest = PruningTable.store(SessionManifest.create("s1", Instant.EPOCH, null), out.entries());
        assertEquals(2, manifest.pruning().size());
        var restored = PruningTable.restore(entries.stream().map(ContextEntry::chunk).toList(), manifest.pruning(), c -> Protection.PROTECTED);
        assertEquals(out.entries().stream().map(ContextEntry::render).toList(), restored.stream().map(ContextEntry::render).toList());
        assertEquals(Protection.CAREFUL, restored.get(1).protection());
    }

    @Test
    void promptBuiltInReplaceAppendAndErrors(@TempDir Path dir) throws Exception {
        var built = CompactorPrompt.build(null, dir, 2, 0.2, Protection.AGGRESSIVE, 12);
        assertTrue(built.contains("Level 2 keeps about 20%"));
        assertTrue(built.contains("\"results\""));
        var replace = new JeorjeConfig.Prompt("replace", "Prune at ${level} with ${maxKeywords}.", "");
        var r = CompactorPrompt.build(replace, dir, 1, 0.5, Protection.CAREFUL, 7);
        assertTrue(r.startsWith("Prune at 1 with 7."));
        assertTrue(r.contains("\"results\""));
        var append = new JeorjeConfig.Prompt("append", "Extra for ${class}.", "");
        var a = CompactorPrompt.build(append, dir, 1, 0.5, Protection.CAREFUL, 7);
        assertTrue(a.contains("You prune text") && a.contains("Extra for CAREFUL."));
        assertThrows(ConfigException.class, () -> CompactorPrompt.build(
            new JeorjeConfig.Prompt("replace", "bad ${nope}", ""), dir, 1, 0.5, Protection.CAREFUL, 7));
        assertThrows(ConfigException.class, () -> CompactorPrompt.build(
            new JeorjeConfig.Prompt("replace", "x", "f.txt"), dir, 1, 0.5, Protection.CAREFUL, 7));
        Files.writeString(dir.resolve("f.txt"), "From file ${target}.");
        var f = CompactorPrompt.build(new JeorjeConfig.Prompt("replace", "", "f.txt"), dir, 1, 0.5, Protection.CAREFUL, 7);
        assertTrue(f.startsWith("From file 50%."));
        assertThrows(ConfigException.class, () -> CompactorPrompt.build(
            new JeorjeConfig.Prompt("replace", "", "missing.txt"), dir, 1, 0.5, Protection.CAREFUL, 7));
    }

    @Test
    void customPromptReachesTheModel() {
        var llm = new ScriptedLlm(r -> keepFirst(r, 0.5, "Widget"));
        var cfg = new Pruner.Config(List.of(1.0, 0.5, 0.2, 0.0), 20, 16_000, 12,
            new JeorjeConfig.Prompt("replace", "CUSTOM ${level}", ""), null);
        var c = chunk(1, "tool_result", "read", body("WidgetService", 8));
        new Pruner(llm, null, TermStats.UNIFORM, cfg).prune(new Pruner.Job(c, Protection.AGGRESSIVE, 1, null), Set.of());
        assertTrue(llm.requests.getFirst().system().startsWith("CUSTOM 1"));
    }
}
