package org.github.lscoughlin.jeorje.core.compaction;

import static org.github.lscoughlin.jeorje.core.compaction.CompactionTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.github.lscoughlin.jeorje.core.session.SessionManifest.PruneEntry;
import org.github.lscoughlin.jeorje.core.session.SessionManifest.Span;
import org.junit.jupiter.api.Test;

class PrunerTest {
    private static Pruner pruner(ScriptedLlm llm, ListStore store) {
        return new Pruner(llm, store, TermStats.UNIFORM, Pruner.Config.defaults());
    }

    /** Asserts that the words of the pruned text are an ordered subsequence of the words of the original. */
    static void assertSubsequence(String original, String pruned) {
        var orig = original.split("\\s+");
        var i = 0;
        for (var w : pruned.split("\\s+")) {
            if (w.equals("...") || w.isEmpty()) continue;
            while (i < orig.length && !orig[i].equals(w)) i++;
            assertTrue(i < orig.length, "word is not in order in the original: " + w);
            i++;
        }
    }

    private static Pruner.Job job(org.github.lscoughlin.jeorje.core.model.Chunk c, Protection p, int level) {
        return new Pruner.Job(c, p, level, null);
    }

    @Test
    void validModelAnswerGivesAnOrderedSubsequence() {
        var c = chunk(1, "tool_result", "read", body("Widget1Service", 8));
        var store = new ListStore();
        store.append(List.of(c));
        var llm = new ScriptedLlm(r -> keepFirst(r, 0.5, "Widget"));
        var res = pruner(llm, store).prune(job(c, Protection.AGGRESSIVE, 1), Set.of());
        assertEquals(PruneResult.Source.MODEL, res.source());
        assertEquals(1, llm.calls.get());
        assertEquals(1, res.level());
        var text = Spans.build(c.text(), res.keptSpans());
        assertSubsequence(c.text(), text);
        assertTrue(text.length() < c.text().length());
        assertTrue(res.keywords().contains("Widget1Service"));
        assertTrue(res.keywords().size() >= 3 && res.keywords().size() <= 12);
        assertTrue(res.keywords().stream().allMatch(k -> c.text().contains(k)));
    }

    @Test
    void invalidAnswersFallBackAfterOneRetry() {
        var c = chunk(1, "tool_result", "read", body("Widget1Service", 8));
        var len = c.text().length();
        var bad = List.of(
            "not json at all",
            "{\"results\":[{\"id\":\"" + c.chunkId() + "\",\"spans\":[[0," + (len + 50) + "]],\"keywords\":[]}]}",
            "{\"results\":[{\"id\":\"" + c.chunkId() + "\",\"spans\":[[0,100],[50,150]],\"keywords\":[]}]}",
            "{\"results\":[{\"id\":\"" + c.chunkId() + "\",\"spans\":[[0,10]],\"keywords\":[]}]}",
            "{\"results\":[{\"id\":\"" + c.chunkId() + "\",\"spans\":[[0," + (len / 2) + "]],\"keywords\":[\"Invented\"]}]}");
        for (var answer : bad) {
            var llm = new ScriptedLlm(r -> answer);
            var res = pruner(llm, null).prune(job(c, Protection.AGGRESSIVE, 1), Set.of());
            assertEquals(2, llm.calls.get(), answer);
            assertEquals(PruneResult.Source.FALLBACK, res.source(), answer);
            assertSubsequence(c.text(), Spans.build(c.text(), res.keptSpans()));
        }
    }

    @Test
    void secondTryCanSucceed() {
        var c = chunk(1, "tool_result", "read", body("Widget1Service", 8));
        var n = new int[1];
        var llm = new ScriptedLlm(r -> n[0]++ == 0 ? "oops" : keepFirst(r, 0.5, "Widget"));
        var res = pruner(llm, null).prune(job(c, Protection.AGGRESSIVE, 1), Set.of());
        assertEquals(2, llm.calls.get());
        assertEquals(PruneResult.Source.MODEL, res.source());
        assertTrue(llm.requests.get(1).messages().getFirst().content().getFirst().toString().contains("previousAnswerFailed"));
    }

    @Test
    void noCompactorUsesTheFallbackPrunerAndMakesNoCall() {
        var c = chunk(1, "tool_result", "read", body("WidgetService", 8));
        var p = new Pruner(null, null, TermStats.UNIFORM, Pruner.Config.defaults());
        assertFalse(p.hasCompactor());
        var res = p.prune(job(c, Protection.AGGRESSIVE, 2), Set.of());
        assertEquals(PruneResult.Source.FALLBACK, res.source());
        assertEquals(2, res.level());
        assertTrue(res.keptChars() < c.text().length() * 0.5);
    }

    @Test
    void protectedChunksNeverChange() {
        var c = chunk(1, "user", null, "USER: " + body("Widget1Service", 8));
        var llm = new ScriptedLlm(r -> keepFirst(r, 0.5, "Widget"));
        var res = pruner(llm, null).prune(job(c, Protection.PROTECTED, 3), Set.of());
        assertEquals(0, res.level());
        assertEquals(0, llm.calls.get());
        assertTrue(res.keptSpans().isEmpty());
        assertEquals(c.text(), PrunedChunks.render(c, null));
    }

    @Test
    void carefulChunkStopsAtLevelTwoAndNeverGoesToLevelThree() {
        var c = chunk(1, "assistant", null, "ASSISTANT: " + body("Widget1Service", 8));
        var llm = new ScriptedLlm(r -> noteAnswer(r, "Widget1Service"));
        var res = pruner(llm, null).prune(job(c, Protection.CAREFUL, 3), Set.of());
        assertEquals(2, res.level());
        assertNotNull(res.note());
        var again = pruner(llm, null).prune(new Pruner.Job(c, Protection.CAREFUL, 3, res.toEntry()), Set.of());
        assertEquals(2, again.level());
        assertEquals(res.note(), again.note());
    }

    private static String noteAnswer(org.github.lscoughlin.jeorje.core.model.LlmRequest r, String keyword) {
        var sb = new StringBuilder("{\"results\":[");
        var first = true;
        for (var c : requested(r)) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":\"").append(c[0]).append("\",\"note\":{\"decision\":[\"process the request\"],")
                .append("\"reason\":[\"without any problem\"],\"files\":[]},\"keywords\":[\"").append(keyword).append("\"]}");
        }
        return sb.append("]}").toString();
    }

    @Test
    void decisionNoteHasOnlyKeywordsAndPathsFromTheOriginal() {
        var text = "ASSISTANT: we chose Widget1Service in src/main/Widget.java because it is simple. " + body("Widget1Service", 6);
        var c = chunk(1, "assistant", null, text);
        var inventedKeyword = new ScriptedLlm(r -> noteAnswer(r, "Invented"));
        var res = pruner(inventedKeyword, null).prune(job(c, Protection.CAREFUL, 2), Set.of());
        assertEquals(PruneResult.Source.FALLBACK, res.source());
        assertEquals(2, inventedKeyword.calls.get());

        var inventedPath = new ScriptedLlm(r -> requested(r).stream().map(x -> "{\"results\":[{\"id\":\"" + x[0]
            + "\",\"note\":{\"decision\":[\"x\"],\"files\":[\"src/Nope.java\"]},\"keywords\":[]}]}").findFirst().get());
        assertEquals(PruneResult.Source.FALLBACK,
            pruner(inventedPath, null).prune(job(c, Protection.CAREFUL, 2), Set.of()).source());

        var good = new ScriptedLlm(r -> "{\"results\":[{\"id\":\"" + c.chunkId() + "\",\"note\":{\"decision\":[\"use Widget1Service\"],"
            + "\"reason\":[\"it is simple\"],\"files\":[\"src/main/Widget.java\"]},\"keywords\":[\"Widget1Service\"]}]}");
        var ok = pruner(good, null).prune(job(c, Protection.CAREFUL, 2), Set.of());
        assertEquals(PruneResult.Source.MODEL, ok.source());
        assertTrue(ok.note().contains("src/main/Widget.java"));
        assertTrue(ok.note().length() < text.length());
        assertTrue(ok.keywords().stream().allMatch(text::contains));
    }

    @Test
    void immediatePathsStayAtEveryLevel() {
        var path = "src/main/java/Config.java";
        var text = body("Widget1Service", 8) + "last " + path + " end\n" + body("Widget1Service", 8);
        var c = chunk(1, "tool_result", "bash", text);
        var llm = new ScriptedLlm(r -> keepFirst(r, 0.5, "Widget"));
        var res = pruner(llm, null).prune(job(c, Protection.AGGRESSIVE, 1), Set.of(path));
        assertTrue(Spans.build(text, res.keptSpans()).contains(path));
        var fb = new Pruner(null, null, TermStats.UNIFORM, Pruner.Config.defaults())
            .prune(job(c, Protection.AGGRESSIVE, 2), Set.of(path));
        assertTrue(Spans.build(text, fb.keptSpans()).contains(path));
    }

    @Test
    void levelThreeKeepsOnlyHeaderAndHintAndKeepsEarlierKeywords() {
        var c = chunk(1, "tool_result", "read", "see src/Config.java\n" + body("Widget1Service", 6) + "NullPointerException at line 412\n");
        var store = new ListStore();
        store.append(List.of(c));
        var l1 = new PruneEntry(c.chunkId(), "AGGRESSIVE", 1, List.of(new Span(0, 20)), null, List.of("Widget1Service"));
        var res = pruner(null, store).prune(new Pruner.Job(c, Protection.AGGRESSIVE, 3, l1), Set.of());
        assertEquals(3, res.level());
        assertTrue(res.keptSpans().isEmpty());
        assertTrue(res.keywords().contains("Widget1Service"));
        var rendered = PrunedChunks.render(c, res.toEntry());
        assertTrue(rendered.contains("pruned L3 (0% kept)"));
        assertTrue(rendered.contains("recall:"));
        assertTrue(rendered.contains("found NullPointerException"));
    }

    @Test
    void levelNeverDecreases() {
        var c = chunk(1, "tool_result", "read", body("Widget1Service", 6));
        var l2 = new PruneEntry(c.chunkId(), "AGGRESSIVE", 2, List.of(new Span(0, 20)), null, List.of("Widget1Service"));
        var res = pruner(null, null).prune(new Pruner.Job(c, Protection.AGGRESSIVE, 1, l2), Set.of());
        assertEquals(2, res.level());
        assertEquals(l2.keptSpans(), res.keptSpans());
    }

    @Test
    void batchesHaveAtMostTwentyChunks() {
        var chunks = new ArrayList<org.github.lscoughlin.jeorje.core.model.Chunk>();
        for (var i = 0; i < 45; i++) chunks.add(chunk("a", i, 0, "tool_result", "read", body("Widget" + i + "Service", 4)));
        var llm = new ScriptedLlm(r -> keepFirst(r, 0.5, "Widget"));
        var jobs = chunks.stream().map(c -> job(c, Protection.AGGRESSIVE, 1)).toList();
        var results = pruner(llm, null).pruneBatch(jobs, Set.of());
        assertEquals(3, llm.calls.get());
        assertEquals(45, results.size());
        for (var r : llm.requests) assertTrue(requested(r).size() <= 20);
        assertTrue(results.stream().allMatch(r -> r.source() == PruneResult.Source.MODEL));
    }

    @Test
    void batchesAlsoStopAtTheTokenLimit() {
        var chunks = new ArrayList<org.github.lscoughlin.jeorje.core.model.Chunk>();
        for (var i = 0; i < 4; i++) chunks.add(chunk("a", i, 0, "tool_result", "read", body("Widget" + i + "Service", 20)));
        var cfg = new Pruner.Config(List.of(1.0, 0.5, 0.2, 0.0), 20, 1200, 12, null, null);
        var llm = new ScriptedLlm(r -> keepFirst(r, 0.5, "Widget"));
        new Pruner(llm, null, TermStats.UNIFORM, cfg)
            .pruneBatch(chunks.stream().map(c -> job(c, Protection.AGGRESSIVE, 1)).toList(), Set.of());
        assertTrue(llm.calls.get() >= 2);
    }

    @Test
    void recallResultBecomesAStubWithSourceIds() {
        var c = chunk(2, "tool_call", "recall", "RECALL query=Widget returned=a:1.0,a:3.1");
        var res = pruner(null, null).prune(job(c, Protection.AGGRESSIVE, 1), Set.of());
        assertEquals(PruneResult.Source.STUB, res.source());
        var rendered = PrunedChunks.render(c, res.toEntry());
        assertTrue(rendered.contains("a:1.0") && rendered.contains("a:3.1"));
        assertTrue(rendered.contains("stub"));
    }

    @Test
    void skillHintHasTheSkillName() {
        var c = chunk(2, "tool_result", "skill", "name: deploy-tool\n" + body("Widget1Service", 6));
        var res = pruner(null, null).prune(job(c, Protection.AGGRESSIVE, 2), Set.of());
        assertTrue(res.keywords().contains("skill:deploy-tool"));
    }
}
