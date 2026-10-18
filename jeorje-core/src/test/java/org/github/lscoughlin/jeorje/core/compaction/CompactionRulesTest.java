package org.github.lscoughlin.jeorje.core.compaction;

import static org.github.lscoughlin.jeorje.core.compaction.CompactionTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.jeorje.core.compaction.ContextClassifier.Context;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.junit.jupiter.api.Test;

/** Rules of D5: error state in turn chunks, immediate paths, concluded searches, and term statistics. */
class CompactionRulesTest {
    private final ContextClassifier classifier = new ContextClassifier();

    private static String turn(String user, String... lines) {
        var sb = new StringBuilder("USER: " + user + "\n");
        for (var l : lines) sb.append(l).append('\n');
        return sb.toString().stripTrailing();
    }

    @Test
    void onlyTheFirstUserChunkOfTheAgentIsProtectedWhenAllChunksAreClassifiedTogether() {
        var first = chunk(1, "mixed", null, turn("build it", "ASSISTANT: ok"));
        var second = chunk(2, "mixed", null, turn("next", "ASSISTANT: ok"));
        var classes = classifier.classify(List.of(first, second), Context.of(5));
        assertEquals(Protection.PROTECTED, classes.get(first.chunkId()));
        assertEquals(Protection.CAREFUL, classes.get(second.chunkId()));
    }

    @Test
    void aFailureInsideATurnChunkIsProtectedUntilTheSameToolSucceeds() {
        var first = chunk(1, "mixed", null, turn("start"));
        var failing = chunk(2, "mixed", null, turn("test", "TOOL_CALL bash {}", "TOOL_RESULT bash [ERROR] BUILD FAILURE",
            "ASSISTANT: it fails"));
        var other = chunk(3, "mixed", null, turn("look", "TOOL_CALL read {}", "TOOL_RESULT read fine"));
        var open = classifier.classify(List.of(first, failing, other), Context.of(5));
        assertEquals(Protection.PROTECTED, open.get(failing.chunkId()), "no later success of bash");
        var fixed = chunk(4, "mixed", null, turn("test again", "TOOL_CALL bash {}", "TOOL_RESULT bash BUILD SUCCESS"));
        var resolved = classifier.classify(List.of(first, failing, other, fixed), Context.of(5));
        assertEquals(Protection.CAREFUL, resolved.get(failing.chunkId()));
        var note = new Context(5, 6, Set.of(), Set.of(failing.chunkId()), Map.of(), 20_000);
        assertEquals(Protection.CAREFUL, classifier.classify(List.of(first, failing, other), note).get(failing.chunkId()));
    }

    @Test
    void aFailureAndALaterSuccessInOneChunkResolveInOrder() {
        var first = chunk(1, "mixed", null, turn("start"));
        var both = chunk(2, "mixed", null, turn("test", "TOOL_RESULT bash [ERROR] one", "TOOL_RESULT bash BUILD SUCCESS"));
        assertEquals(Protection.CAREFUL, classifier.classify(List.of(first, both), Context.of(5)).get(both.chunkId()));
    }

    @Test
    void immediatePathsComeFromFileToolCallsOfRecentTurnChunks() {
        var oldTurn = chunk(1, "mixed", null, turn("a", "TOOL_CALL read {\"path\":\"src/Old.java\"}"));
        var recent = chunk(9, "mixed", null, turn("b", "TOOL_CALL read {\"path\":\"src/Read.java\"}",
            "TOOL_RESULT read import data/noise.txt", "TOOL_CALL edit {\"path\":\"src/Edit.java\",\"old\":\"x\"}",
            "TOOL_CALL bash {\"command\":\"ls build/other\"}"));
        var ctx = Context.of(10);
        var all = List.of(oldTurn, recent);
        var paths = classifier.immediatePaths(all, ctx, classifier.classify(all, ctx));
        assertTrue(paths.contains("src/Read.java"));
        assertTrue(paths.contains("src/Edit.java"));
        assertFalse(paths.contains("src/Old.java"), "outside the protected turns");
        assertFalse(paths.contains("build/other"), "bash is not a file tool");
        assertFalse(paths.contains("data/noise.txt"), "file content is not a path of interest");
    }

    @Test
    void immediatePathsUseTheProtectedTurnCount() {
        var c = chunk(5, "mixed", null, turn("b", "TOOL_CALL write {\"path\":\"src/Mid.java\"}"));
        var wide = new Context(10, 6, Set.of(), Set.of(), Map.of(), 20_000);
        var narrow = new Context(10, 3, Set.of(), Set.of(), Map.of(), 20_000);
        assertTrue(classifier.immediatePaths(List.of(c), wide, Map.of()).contains("src/Mid.java"));
        assertFalse(classifier.immediatePaths(List.of(c), narrow, Map.of()).contains("src/Mid.java"));
    }

    @Test
    void pathsOfAProtectedErrorInATurnChunkAreImmediate() {
        var first = chunk(1, "mixed", null, turn("start"));
        var failing = chunk(2, "mixed", null, turn("test", "TOOL_RESULT bash [ERROR] src/Broken.java:12 failed"));
        var ctx = Context.of(40);
        var all = List.of(first, failing);
        var paths = classifier.immediatePaths(all, ctx, classifier.classify(all, ctx));
        assertTrue(paths.stream().anyMatch(p -> p.startsWith("src/Broken.java")));
    }

    private static final String GREP = "TOOL_RESULT grep\nsrc/Foo.java:10: class FooService\nsrc/Bar.java:3: use";

    @Test
    void aSearchThatALaterEditUsedIsConcluded() {
        var search = chunk(2, "tool_result", "grep", "src/Foo.java:10: class FooService\nsrc/Bar.java:3: use FooService");
        var edit = chunk(3, "mixed", null, turn("fix", "TOOL_CALL edit {\"path\":\"src/Foo.java\",\"new\":\"x\"}"));
        var all = List.of(search, edit);
        var ctx = Context.of(10);
        var classes = classifier.classify(all, ctx);
        assertEquals(Set.of(search.chunkId()), classifier.concludedSearches(all, ctx, classes));
    }

    @Test
    void aSearchThatALaterAnswerUsedIsConcluded() {
        var search = chunk(2, "tool_result", "glob", "docs/guide/Install.md\ndocs/guide/Usage.md");
        var answer = chunk(3, "assistant", null, "ASSISTANT: The steps are in Install.md.");
        var all = List.of(search, answer);
        var ctx = Context.of(10);
        assertEquals(Set.of(search.chunkId()), classifier.concludedSearches(all, ctx, classifier.classify(all, ctx)));
    }

    @Test
    void aSearchInTheSameTurnAsTheEditIsConcluded() {
        var main = chunk("a", 4, 0, "mixed", null, turn("fix", "TOOL_CALL grep {}", "TOOL_CALL edit {\"path\":\"src/Foo.java\"}"));
        var search = chunk("a", 4, 1, "tool_result", "grep", "src/Foo.java:10: class FooService");
        var all = List.of(main, search);
        var ctx = Context.of(10);
        assertEquals(Set.of(search.chunkId()), classifier.concludedSearches(all, ctx, classifier.classify(all, ctx)));
    }

    @Test
    void aSearchWithoutALaterUseOrWithOnlyAnUnrelatedUseIsNotConcluded() {
        var search = chunk(2, "tool_result", "grep", "src/Foo.java:10: class FooService");
        var unrelated = chunk(3, "mixed", null, turn("fix", "TOOL_CALL edit {\"path\":\"src/Other.java\"}"));
        var bash = chunk(4, "mixed", null, turn("run", "TOOL_CALL bash {\"command\":\"cat src/Foo.java\"}"));
        var before = chunk(1, "assistant", null, "ASSISTANT: I will look at src/Foo.java");
        var all = List.of(before, search, unrelated, bash);
        var ctx = Context.of(10);
        assertTrue(classifier.concludedSearches(all, ctx, classifier.classify(all, ctx)).isEmpty());
    }

    @Test
    void aSearchOfAnotherAgentDoesNotCount() {
        var search = chunk("a", 2, 0, "tool_result", "grep", "src/Foo.java:10: class FooService");
        var edit = chunk("b", 3, 0, "mixed", null, turn("fix", "TOOL_CALL edit {\"path\":\"src/Foo.java\"}"));
        var all = List.of(search, edit);
        var ctx = Context.of(10);
        assertTrue(classifier.concludedSearches(all, ctx, classifier.classify(all, ctx)).isEmpty());
    }

    // ---- compactor ----

    private static ContextCompactor compactor(int budget) {
        var pruner = new Pruner(null, null, TermStats.UNIFORM, Pruner.Config.defaults());
        return new ContextCompactor(pruner, CompactionSettings.defaults(budget), new AgentId("a"));
    }

    private static ContextEntry entry(int turn, String tool, int lines) {
        return ContextEntry.of(chunk("a", turn, 0, "tool_result", tool, body("WidgetService" + turn, lines)),
            Protection.AGGRESSIVE);
    }

    @Test
    void aConcludedSearchGoesToLevelThreeAtTheFirstCompaction() {
        var plain = entry(2, "grep", 40);
        var concluded = entry(3, "grep", 40);
        var total = plain.tokens() + concluded.tokens();
        var out = compactor(total).compact(List.of(plain, concluded), 10, 0, Set.of(),
            Set.of(concluded.chunk().chunkId()));
        assertEquals(2, out.entries().get(0).level(), "not concluded: level 2 until it is old enough");
        assertEquals(3, out.entries().get(1).level());
    }

    @Test
    void concludedSearchesComeFirstInTheEvictionOrder() {
        var large = ContextEntry.of(chunk("a", 1, 0, "tool_result", "read", "x".repeat(9000)), Protection.AGGRESSIVE);
        var small = entry(2, "grep", 3);
        var list = List.of(large, small);
        var c = compactor(1000);
        assertEquals(List.of(0, 1), c.evictionOrder(list, 40));
        assertEquals(List.of(1, 0), c.evictionOrder(list, 40, Set.of(small.chunk().chunkId())));
    }

    @Test
    void theTriggersCountTheWholeWorkingContext() {
        var entries = List.of(entry(1, "read", 20));
        var t = entries.getFirst().tokens();
        var budget = t * 4;
        assertFalse(compactor(budget).compact(entries, 40, 0, Set.of()).soft());
        var whole = compactor(budget).compact(entries, 40, t * 2, Set.of());
        assertTrue(whole.soft(), "the overhead of the request makes the whole context pass the soft limit");
        assertEquals(1, whole.chunksPruned());
    }

    @Test
    void settingsCarryTheProtectedRecentTurns() {
        assertEquals(6, CompactionSettings.defaults(1000).protectedRecentTurns());
        assertEquals(6, new CompactionSettings(1000, 50, 80, 6, 12, 20, 16_000).protectedRecentTurns());
        assertEquals(4, new CompactionSettings(1000, 50, 80, 6, 12, 20, 16_000, 4).protectedRecentTurns());
        assertEquals(6, compactor(1000).settings().protectedRecentTurns());
    }

    // ---- pruner ----

    @Test
    void aLevelThreeChunkKeepsTheImmediatePathsInItsTopicsLine() {
        var c = chunk(1, "tool_result", "read", "header\nsrc/Kept.java is here\n" + body("Widget", 5));
        var pruner = new Pruner(null, null, TermStats.UNIFORM, Pruner.Config.defaults());
        var with = pruner.prune(new Pruner.Job(c, Protection.AGGRESSIVE, 3, null), Set.of("src/Kept.java", "other/Gone.java"));
        assertEquals(3, with.level());
        assertTrue(with.note().contains("files src/Kept.java"), with.note());
        assertFalse(with.note().contains("Gone.java"), "paths that are not in the chunk stay out");
        var without = pruner.prune(new Pruner.Job(c, Protection.AGGRESSIVE, 3, null), Set.of());
        assertFalse(without.note().contains("files "), without.note());
    }

    // ---- term statistics ----

    @Test
    void termStatsWeighRareTermsAboveCommonOnes() {
        var store = new ListStore();
        var chunks = new ArrayList<org.github.lscoughlin.jeorje.core.model.Chunk>();
        for (var i = 0; i < 30; i++) chunks.add(chunk("a", i + 1, 0, "tool_result", "read", "common words " + (i == 0 ? "raretoken" : "")));
        chunks.add(chunk("a", 40, 0, "tool_call", "recall", "RECALL query=common returned=a:1.0"));
        store.append(chunks);
        var stats = TermStats.of(store, () -> chunks.size());
        assertTrue(stats.idf("raretoken") > stats.idf("common") + 1.0, stats.idf("raretoken") + " " + stats.idf("common"));
        assertEquals(stats.idf("common"), stats.idf("common"), "the second call uses the cache");
        assertTrue(stats.idf("common") >= 1.0);
        assertEquals(1.0, stats.idf("---"));
    }

    @Test
    void termStatsRefreshWhenTheIndexGrows() {
        var count = new AtomicInteger(20);
        var calls = new AtomicInteger();
        var inner = new ListStore();
        var counting = new org.github.lscoughlin.jeorje.core.port.ContextStore() {
            @Override public void append(List<org.github.lscoughlin.jeorje.core.model.Chunk> c) { inner.append(c); }
            @Override public List<org.github.lscoughlin.jeorje.core.port.Hit> search(
                org.github.lscoughlin.jeorje.core.port.HistoryQuery q) {
                calls.incrementAndGet();
                return inner.search(q);
            }
            @Override public java.util.Optional<org.github.lscoughlin.jeorje.core.model.Chunk> get(String id) { return inner.get(id); }
            @Override public List<org.github.lscoughlin.jeorje.core.model.Chunk> window(String id, int b, int a) { return List.of(); }
            @Override public void close() {}
        };
        counting.append(List.of(chunk(1, "tool_result", "read", "alpha")));
        var stats = TermStats.of(counting, count::get);
        stats.idf("alpha");
        stats.idf("alpha");
        assertEquals(1, calls.get());
        count.set(21);
        stats.idf("alpha");
        assertEquals(1, calls.get(), "a growth of 10% or less keeps the cache");
        count.set(40);
        stats.idf("alpha");
        assertEquals(2, calls.get());
    }

    @Test
    void uniformStatsStayUniform() {
        assertEquals(1.0, TermStats.UNIFORM.idf("anything"));
    }
}
