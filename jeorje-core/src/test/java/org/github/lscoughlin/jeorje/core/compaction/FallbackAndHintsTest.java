package org.github.lscoughlin.jeorje.core.compaction;

import static org.github.lscoughlin.jeorje.core.compaction.CompactionTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class FallbackAndHintsTest {
    @Test
    void fallbackRemovesBlankRepeatedAndStopWordsAndKeepsTheOrder() {
        var text = "the cache is full\n\n\nthe cache is full\nthe parser reads the tokens from the input\n"
            + "the writer emits the result to the sink\n";
        var spans = new FallbackPruner(TermStats.UNIFORM).prune(text, 0.5, List.of());
        var out = Spans.build(text, spans);
        PrunerTest.assertSubsequence(text, out);
        assertTrue(Spans.size(spans) <= text.length() * 0.5, out);
        assertFalse(out.contains("\n\n"));
        assertTrue(out.split("cache", -1).length - 1 <= 1);
    }

    @Test
    void fallbackKeepsPathErrorAndNumberLines() {
        var filler = "the quiet worker walks around the old garden and sees nothing\n";
        var text = filler + "open src/main/App.java now\n" + filler.replace("quiet", "calm") + "build failed here\n"
            + filler.replace("quiet", "loud") + "total 4096 bytes\n" + filler.replace("quiet", "tall");
        var out = Spans.build(text, new FallbackPruner(TermStats.UNIFORM).prune(text, 0.3, List.of()));
        assertTrue(out.contains("src/main/App.java"));
        assertTrue(out.contains("failed"));
        assertTrue(out.contains("4096"));
        assertFalse(out.contains("garden"));
        PrunerTest.assertSubsequence(text, out);
    }

    @Test
    void fallbackPrefersLinesWithRareTerms() {
        var text = "common common common\nrare zebra here\ncommon common again\n";
        TermStats stats = t -> t.equals("zebra") || t.equals("rare") ? 5.0 : 0.1;
        var out = Spans.build(text, new FallbackPruner(stats).prune(text, 0.35, List.of()));
        assertTrue(out.contains("zebra"));
        assertFalse(out.contains("again"));
    }

    @Test
    void spanCheckRejectsOverlapAndOutOfRange() {
        var s = new org.github.lscoughlin.jeorje.core.session.SessionManifest.Span(0, 5);
        assertTrue(Spans.check(List.of(s, new org.github.lscoughlin.jeorje.core.session.SessionManifest.Span(3, 8)), "0123456789").isPresent());
        assertTrue(Spans.check(List.of(new org.github.lscoughlin.jeorje.core.session.SessionManifest.Span(5, 20)), "0123456789").isPresent());
        assertTrue(Spans.check(List.of(s, new org.github.lscoughlin.jeorje.core.session.SessionManifest.Span(5, 8)), "0123456789").isEmpty());
    }

    @Test
    void hintFindsTheChunkAndDropsKeywordsThatAreNotDistinctive() {
        var store = new ListStore();
        for (var i = 0; i < 25; i++) store.append(List.of(chunk("a", i, 0, "tool_result", "read", "common service log entry number " + i)));
        var target = chunk("a", 30, 0, "tool_result", "read", "common service Zorblax handler in src/Zorblax.java throws QuuxException");
        store.append(List.of(target));
        var hints = new KeywordHints(store, TermStats.of(store, 26), 12);
        var hint = hints.build(target, List.of("service", "Zorblax"));
        assertFalse(hint.keywords().contains("service"));
        assertTrue(hint.keywords().contains("Zorblax"));
        assertTrue(hint.keywords().size() >= 3 && hint.keywords().size() <= 12);
        assertTrue(hint.verified());
        var top = store.search(new org.github.lscoughlin.jeorje.core.port.HistoryQuery(KeywordHints.query(hint.keywords()),
            org.github.lscoughlin.jeorje.core.port.Syntax.TEXT, org.github.lscoughlin.jeorje.core.port.Filters.none(), 3));
        assertTrue(top.stream().anyMatch(h -> h.chunk().chunkId().equals(target.chunkId())));
        assertTrue(hint.keywords().stream().allMatch(k -> target.text().contains(k)));
    }

    @Test
    void hintAddsKeywordsUntilTheChunkIsInTheTopThree() {
        var store = new ListStore();
        for (var i = 0; i < 6; i++) store.append(List.of(chunk("a", i, 0, "tool_result", "read", "alpha beta gamma delta rival" + i)));
        var target = chunk("a", 9, 0, "tool_result", "read", "alpha beta gamma delta epsilon");
        store.append(List.of(target));
        var hint = new KeywordHints(store, TermStats.UNIFORM, 12).build(target, List.of("alpha", "beta", "gamma"));
        assertTrue(hint.verified());
        assertTrue(hint.keywords().contains("epsilon") || hint.keywords().size() > 3);
    }

    @Test
    void recallChunksDoNotCountAsMatches() {
        var store = new ListStore();
        for (var i = 0; i < 30; i++) store.append(List.of(chunk("a", i, 0, "tool_call", "recall", "RECALL Zorblax returned=a:1.0")));
        var target = chunk("a", 40, 0, "tool_result", "read", "Zorblax Quux Frobnicate");
        store.append(List.of(target));
        var hint = new KeywordHints(store, TermStats.UNIFORM, 12).build(target, List.of("Zorblax"));
        assertTrue(hint.keywords().contains("Zorblax"));
    }

    @Test
    void roleSettingsWithoutCompactor() {
        var roles = new ModelRoles("m", null, "");
        assertFalse(roles.compactorSet());
        assertTrue(roles.pruningModel().isEmpty());
        assertFalse(roles.advisorToolEnabled());
        var set = new ModelRoles("m", "fast", "big");
        assertEquals("fast", set.pruningModel().orElseThrow());
        assertTrue(set.advisorToolEnabled());
    }
}
