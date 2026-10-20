package org.github.lscoughlin.jeorje.index.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.port.Embedder;
import org.github.lscoughlin.jeorje.core.port.Filters;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;
import org.github.lscoughlin.jeorje.core.port.Syntax;
import org.github.lscoughlin.jeorje.testkit.StubEmbedder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LuceneContextStoreTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir Path tmp;

    private static Chunk chunk(String agent, int turn, int part, String kind, String tool, String text) {
        return new Chunk(agent + ":" + turn + "." + part, "s1", agent, turn, part,
            T0.plusSeconds(turn * 60L), kind, tool, text);
    }

    private static HistoryQuery q(String text, Syntax syntax, Filters filters, int limit) {
        return new HistoryQuery(text, syntax, filters, limit);
    }

    private static Filters filters(Set<String> agents, OptionalInt from, OptionalInt to, Set<String> kinds,
                                   Set<String> tools) {
        return new Filters(agents, from, to, Optional.empty(), Optional.empty(), kinds, tools);
    }

    private LuceneContextStore store(Embedder e) {
        return LuceneContextStore.open(tmp.resolve("index"), e, LuceneContextStore.Options.defaults()
            .withRetryInterval(Duration.ZERO));
    }

    private static List<String> ids(List<org.github.lscoughlin.jeorje.core.port.Hit> hits) {
        return hits.stream().map(h -> h.chunk().chunkId()).toList();
    }

    @Test
    void indexPathFollowsLayout() {
        assertEquals(tmp.resolve(".jeorje/sessions/abc/index"), LuceneContextStore.indexPath(tmp, "abc"));
    }

    @Test
    void textSearchSplitsCamelCaseAndKeepsOriginal() {
        try (var s = store(null)) {
            s.append(List.of(
                chunk("a", 1, 0, "assistant", null, "Open the ContextStore class in src/main/Foo.java"),
                chunk("a", 2, 0, "assistant", null, "unrelated words about weather")));
            assertEquals(List.of("a:1.0"), ids(s.search(q("context", Syntax.TEXT, Filters.none(), 5))));
            assertEquals(List.of("a:1.0"), ids(s.search(q("ContextStore", Syntax.TEXT, Filters.none(), 5))));
            assertEquals(List.of("a:1.0"), ids(s.search(q("foo", Syntax.TEXT, Filters.none(), 5))));
            var hits = s.search(q("contextstore", Syntax.TEXT, Filters.none(), 5));
            assertTrue(hits.get(0).snippet().contains("["), hits.get(0).snippet());
        }
    }

    @Test
    void textModeEscapesSpecialCharactersAndSupportsPhrases() {
        try (var s = store(null)) {
            s.append(List.of(
                chunk("a", 1, 0, "assistant", null, "the quick brown fox"),
                chunk("a", 2, 0, "assistant", null, "brown quick the fox")));
            assertEquals(List.of("a:1.0"), ids(s.search(q("\"quick brown\"", Syntax.TEXT, Filters.none(), 5))));
            assertEquals(2, s.search(q("fox AND (", Syntax.TEXT, Filters.none(), 5)).size());
            assertEquals(2, s.search(q("fox: [", Syntax.TEXT, Filters.none(), 5)).size());
        }
    }

    @Test
    void rawModeUsesClassicSyntax() {
        try (var s = store(null)) {
            s.append(List.of(
                chunk("a", 1, 0, "assistant", null, "alpha beta"),
                chunk("a", 2, 0, "assistant", null, "alpha gamma")));
            assertEquals(List.of("a:2.0"), ids(s.search(q("alpha AND NOT beta", Syntax.RAW, Filters.none(), 5))));
            assertEquals(List.of("a:1.0"), ids(s.search(q("text:beta", Syntax.RAW, Filters.none(), 5))));
            assertThrows(IllegalArgumentException.class, () -> s.search(q("alpha AND (", Syntax.RAW, Filters.none(), 5)));
        }
    }

    @Test
    void vectorModeFindsExactTextAndErrorsWithoutEmbedder() {
        try (var s = store(new StubEmbedder())) {
            s.append(List.of(
                chunk("a", 1, 0, "assistant", null, "first topic"),
                chunk("a", 2, 0, "assistant", null, "second topic"),
                chunk("a", 3, 0, "assistant", null, "third topic")));
            var hits = s.search(q("second topic", Syntax.VECTOR, Filters.none(), 3));
            assertEquals("a:2.0", hits.get(0).chunk().chunkId());
            assertEquals("second topic", hits.get(0).snippet());
        }
        try (var s = store(null)) {
            assertThrows(VectorUnavailableException.class,
                () -> s.search(q("anything", Syntax.VECTOR, Filters.none(), 3)));
        }
    }

    @Test
    void hybridFusesTextAndVectorLegs() {
        try (var s = store(new StubEmbedder())) {
            s.append(List.of(
                chunk("a", 1, 0, "assistant", null, "zebra stripes pattern"),
                chunk("a", 2, 0, "assistant", null, "completely other words"),
                chunk("a", 3, 0, "assistant", null, "more other content")));
            var hits = s.search(q("zebra stripes pattern", Syntax.HYBRID, Filters.none(), 3));
            assertEquals("a:1.0", hits.get(0).chunk().chunkId());
            // Both legs rank this chunk first: score is 2/(60+1).
            assertEquals(2.0f / 61.0f, hits.get(0).score(), 1e-5);
            // Vector-only hits appear too: the text leg matches one chunk, the vector leg returns all.
            assertEquals(3, hits.size());
        }
    }

    @Test
    void hybridFallsBackToTextWithoutEmbedder() {
        try (var s = store(null)) {
            s.append(List.of(chunk("a", 1, 0, "assistant", null, "needle in haystack"),
                chunk("a", 2, 0, "assistant", null, "hay")));
            var hits = s.search(q("needle", Syntax.HYBRID, Filters.none(), 5));
            assertEquals(List.of("a:1.0"), ids(hits));
        }
    }

    @Test
    void hybridFallsBackToTextWhenVectorLegFails() {
        var failing = new AtomicBoolean();
        var delegate = new StubEmbedder();
        var flaky = new Embedder() {
            public String modelId() { return delegate.modelId(); }
            public int dimensions() { return delegate.dimensions(); }
            public float[] embed(String text) {
                if (failing.get()) throw new IllegalStateException("boom");
                return delegate.embed(text);
            }
            public List<float[]> embedBatch(List<String> texts) {
                return texts.stream().map(this::embed).toList();
            }
        };
        try (var s = store(flaky)) {
            s.append(List.of(chunk("a", 1, 0, "assistant", null, "needle in haystack"),
                chunk("a", 2, 0, "assistant", null, "other")));
            failing.set(true);
            assertEquals(List.of("a:1.0"), ids(s.search(q("needle", Syntax.HYBRID, Filters.none(), 5))));
        }
    }

    @Test
    void filtersApplyBeforeRanking() {
        try (var s = store(new StubEmbedder())) {
            var list = new ArrayList<Chunk>();
            // Many strong matches for agent "other"; one weak match for agent "mine".
            for (var i = 0; i < 80; i++) {
                list.add(chunk("other", i, 0, "assistant", null, "needle needle needle needle"));
            }
            list.add(chunk("mine", 1, 0, "assistant", null, "a single needle among many words here today"));
            s.append(list);
            var only = filters(Set.of("mine"), OptionalInt.empty(), OptionalInt.empty(), Set.of(), Set.of());
            for (var syntax : List.of(Syntax.TEXT, Syntax.HYBRID, Syntax.VECTOR, Syntax.RAW)) {
                var hits = s.search(q("needle", syntax, only, 3));
                assertTrue(ids(hits).contains("mine:1.0"), syntax + " " + ids(hits));
                assertTrue(hits.stream().allMatch(h -> h.chunk().agentId().equals("mine")), syntax.name());
            }
        }
    }

    @Test
    void filtersByTurnTimeKindAndTool() {
        try (var s = store(null)) {
            s.append(List.of(
                chunk("a", 1, 0, "user", null, "keyword one"),
                chunk("a", 2, 0, "tool_result", "bash", "keyword two"),
                chunk("a", 3, 0, "tool_result", "grep", "keyword three"),
                chunk("a", 4, 0, "subagent_summary", null, "keyword four")));
            var turns = filters(Set.of(), OptionalInt.of(2), OptionalInt.of(3), Set.of(), Set.of());
            assertEquals(Set.of("a:2.0", "a:3.0"), Set.copyOf(ids(s.search(q("keyword", Syntax.TEXT, turns, 10)))));
            var time = new Filters(Set.of(), OptionalInt.empty(), OptionalInt.empty(),
                Optional.of(T0.plusSeconds(60 * 3)), Optional.empty(), Set.of(), Set.of());
            assertEquals(Set.of("a:3.0", "a:4.0"), Set.copyOf(ids(s.search(q("keyword", Syntax.TEXT, time, 10)))));
            var kind = filters(Set.of(), OptionalInt.empty(), OptionalInt.empty(), Set.of("subagent_summary"), Set.of());
            assertEquals(List.of("a:4.0"), ids(s.search(q("keyword", Syntax.TEXT, kind, 10))));
            var tool = filters(Set.of(), OptionalInt.empty(), OptionalInt.empty(), Set.of(), Set.of("bash"));
            assertEquals(List.of("a:2.0"), ids(s.search(q("keyword", Syntax.TEXT, tool, 10))));
        }
    }

    @Test
    void summaryChunkKeepsItsSourceAgentInTheIndexField() {
        try (var s = store(null)) {
            var summary = new Chunk("parent:1.1000", "s1", "parent", 1, 1000, T0, "subagent_summary", null,
                "Done: wrote the parser", "sub7");
            s.append(List.of(summary, chunk("parent", 1, 0, "user", null, "Done: wrote the other thing")));
            var hits = s.search(q("parser", Syntax.TEXT, Filters.none(), 10));
            assertEquals("sub7", hits.getFirst().chunk().sourceAgentId());
            assertEquals("parent", hits.getFirst().chunk().agentId());
            var raw = s.search(q("source_agent_id:sub7", Syntax.RAW, Filters.none(), 10));
            assertEquals(List.of("parent:1.1000"), ids(raw));
            var plain = s.search(q("other", Syntax.TEXT, Filters.none(), 10));
            org.junit.jupiter.api.Assertions.assertNull(plain.getFirst().chunk().sourceAgentId());
        }
    }

    @Test
    void recallChunksAreSkippedUnlessRequested() {
        try (var s = store(new StubEmbedder())) {
            s.append(List.of(
                chunk("a", 1, 0, "tool_call", "recall", "query about widgets"),
                chunk("a", 2, 0, "assistant", null, "widgets are discussed here")));
            for (var syntax : List.of(Syntax.TEXT, Syntax.HYBRID, Syntax.RAW, Syntax.VECTOR)) {
                assertEquals(List.of("a:2.0"), ids(s.search(q("widgets", syntax, Filters.none(), 5))), syntax.name());
            }
            var recall = filters(Set.of(), OptionalInt.empty(), OptionalInt.empty(), Set.of(), Set.of("recall"));
            assertEquals(List.of("a:1.0"), ids(s.search(q("widgets", Syntax.TEXT, recall, 5))));
        }
    }

    @Test
    void getAndWindow() {
        try (var s = store(null)) {
            s.append(List.of(
                chunk("a", 1, 0, "user", null, "one"),
                chunk("a", 1, 1, "assistant", null, "two"),
                chunk("a", 2, 0, "user", null, "three"),
                chunk("a", 3, 0, "user", null, "four"),
                chunk("b", 1, 0, "user", null, "other agent")));
            assertEquals("three", s.get("a:2.0").orElseThrow().text());
            assertTrue(s.get("nope").isEmpty());
            assertEquals(List.of("a:1.1", "a:2.0", "a:3.0"), ids2(s.window("a:2.0", 1, 1)));
            assertEquals(List.of("a:1.0", "a:1.1", "a:2.0", "a:3.0"), ids2(s.window("a:2.0", 5, 5)));
            assertEquals(List.of("a:2.0"), ids2(s.window("a:2.0", 0, 0)));
            assertTrue(s.window("missing", 1, 1).isEmpty());
        }
    }

    private static List<String> ids2(List<Chunk> chunks) {
        return chunks.stream().map(Chunk::chunkId).toList();
    }

    @Test
    void appendIsSearchableOnReturnAndSurvivesReopen() {
        try (var s = store(new StubEmbedder())) {
            s.append(List.of(chunk("a", 1, 0, "user", null, "persistent text")));
            assertEquals(1, s.search(q("persistent", Syntax.TEXT, Filters.none(), 5)).size());
        }
        try (var s = store(new StubEmbedder())) {
            assertEquals(1, s.search(q("persistent", Syntax.HYBRID, Filters.none(), 5)).size());
        }
    }

    @Test
    void secondWriterIsRefused() {
        try (var s = store(null)) {
            assertThrows(IllegalStateException.class, () -> store(null));
        }
    }

    @Test
    void driftOnChangedModelOrDimensionsFailsAndRebuildFixes() {
        try (var s = store(new StubEmbedder())) {
            s.append(List.of(chunk("a", 1, 0, "user", null, "drifting text")));
        }
        var other = new Embedder() {
            public String modelId() { return "other-model"; }
            public int dimensions() { return 384; }
            public float[] embed(String text) { return new StubEmbedder().embed(text + "x"); }
            public List<float[]> embedBatch(List<String> texts) { return texts.stream().map(this::embed).toList(); }
        };
        assertThrows(EmbeddingDriftException.class, () -> store(other));
        var fewer = new Embedder() {
            public String modelId() { return "stub-embedder"; }
            public int dimensions() { return 8; }
            public float[] embed(String text) { var v = new float[8]; v[0] = 1; return v; }
            public List<float[]> embedBatch(List<String> texts) { return texts.stream().map(this::embed).toList(); }
        };
        assertThrows(EmbeddingDriftException.class, () -> store(fewer));
        try (var s = LuceneContextStore.open(tmp.resolve("index"), other,
            LuceneContextStore.Options.defaults().withRebuild(true).withRetryInterval(Duration.ZERO))) {
            assertEquals(1, s.search(q("drifting", Syntax.HYBRID, Filters.none(), 5)).size());
            assertEquals(0, s.pendingCount());
        }
        // After the rebuild the new model is the recorded model.
        try (var s = LuceneContextStore.open(tmp.resolve("index"), other, LuceneContextStore.Options.defaults()
            .withRetryInterval(Duration.ZERO))) {
            assertEquals(1, s.search(q("drifting", Syntax.VECTOR, Filters.none(), 5)).size());
        }
    }

    @Test
    void textOnlyIndexCanLaterGainAnEmbedder() {
        try (var s = store(null)) {
            s.append(List.of(chunk("a", 1, 0, "user", null, "early text")));
            assertEquals(1, s.pendingCount());
        }
        try (var s = store(new StubEmbedder())) {
            assertEquals(1, s.pendingCount());
            assertEquals(1, s.retryPending());
            assertEquals(0, s.pendingCount());
            assertEquals("a:1.0", s.search(q("early text", Syntax.VECTOR, Filters.none(), 1)).get(0).chunk().chunkId());
        }
    }

    @Test
    void oldSchemaTriggersReindexFromStoredFields() throws Exception {
        var dir = tmp.resolve("index");
        try (var d = FSDirectory.open(dir);
             var w = new IndexWriter(d, new IndexWriterConfig(CodeAnalyzer.forIndexing()))) {
            var doc = new Document();
            doc.add(new StringField("chunk_id", "a:1.0", Field.Store.YES));
            doc.add(new StringField("session_id", "s1", Field.Store.YES));
            doc.add(new StringField("agent_id", "a", Field.Store.YES));
            doc.add(new IntPoint("turn", 1));
            doc.add(new StoredField("turn", 1));
            doc.add(new StoredField("part", 0));
            doc.add(new LongPoint("timestamp", T0.toEpochMilli()));
            doc.add(new StoredField("timestamp", T0.toEpochMilli()));
            doc.add(new StringField("kind", "user", Field.Store.YES));
            doc.add(new TextField("text", "legacy content", Field.Store.YES));
            w.addDocument(doc);
            w.setLiveCommitData(java.util.Map.of("schema_version", "0").entrySet());
            w.commit();
        }
        try (var s = store(new StubEmbedder())) {
            var got = s.get("a:1.0").orElseThrow();
            assertEquals("legacy content", got.text());
            assertEquals(T0, got.timestamp());
            assertEquals(0, s.pendingCount());
            // The reindex made the part and vector fields available.
            assertEquals("a:1.0", s.search(q("legacy content", Syntax.VECTOR, Filters.none(), 1)).get(0).chunk().chunkId());
            assertEquals(1, s.window("a:1.0", 1, 1).size());
        }
    }

    @Test
    void embeddingFailureIndexesTextOnlyAndRetryRecovers() {
        var failing = new AtomicBoolean(true);
        var delegate = new StubEmbedder();
        var flaky = new Embedder() {
            public String modelId() { return delegate.modelId(); }
            public int dimensions() { return delegate.dimensions(); }
            public float[] embed(String text) {
                if (failing.get() && text.contains("bad")) throw new IllegalStateException("boom");
                return delegate.embed(text);
            }
            public List<float[]> embedBatch(List<String> texts) {
                return texts.stream().map(this::embed).toList();
            }
        };
        try (var s = store(flaky)) {
            s.append(List.of(chunk("a", 1, 0, "user", null, "good chunk"),
                chunk("a", 2, 0, "user", null, "bad chunk")));
            assertEquals(1, s.pendingCount());
            assertEquals(2, s.search(q("chunk", Syntax.TEXT, Filters.none(), 5)).size());
            assertEquals(0, s.retryPending());
            failing.set(false);
            assertEquals(1, s.retryPending());
            assertEquals(0, s.pendingCount());
            assertEquals("a:2.0", s.search(q("bad chunk", Syntax.VECTOR, Filters.none(), 1)).get(0).chunk().chunkId());
        }
    }

    @Test
    void backgroundRetryRuns() throws Exception {
        var failing = new AtomicBoolean(true);
        var delegate = new StubEmbedder();
        var flaky = new Embedder() {
            public String modelId() { return delegate.modelId(); }
            public int dimensions() { return delegate.dimensions(); }
            public float[] embed(String text) {
                if (failing.get()) throw new IllegalStateException("boom");
                return delegate.embed(text);
            }
            public List<float[]> embedBatch(List<String> texts) { return texts.stream().map(this::embed).toList(); }
        };
        try (var s = LuceneContextStore.open(tmp.resolve("index"), flaky,
            LuceneContextStore.Options.defaults().withRetryInterval(Duration.ofMillis(50)))) {
            s.append(List.of(chunk("a", 1, 0, "user", null, "later")));
            assertEquals(1, s.pendingCount());
            failing.set(false);
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (s.pendingCount() > 0 && System.nanoTime() < deadline) Thread.sleep(25);
            assertEquals(0, s.pendingCount());
        }
    }

    @Test
    void largeAppendIsBatched() {
        var calls = new ArrayList<Integer>();
        var delegate = new StubEmbedder();
        var counting = new Embedder() {
            public String modelId() { return delegate.modelId(); }
            public int dimensions() { return delegate.dimensions(); }
            public float[] embed(String text) { return delegate.embed(text); }
            public List<float[]> embedBatch(List<String> texts) {
                calls.add(texts.size());
                return delegate.embedBatch(texts);
            }
        };
        try (var s = store(counting)) {
            var list = new ArrayList<Chunk>();
            for (var i = 0; i < 150; i++) list.add(chunk("a", i, 0, "user", null, "text " + i));
            s.append(list);
            assertEquals(List.of(64, 64, 22), calls);
        }
    }

    @Test
    void searchRunsConcurrentlyWithAppend() throws Exception {
        try (var s = store(new StubEmbedder())) {
            s.append(List.of(chunk("a", 0, 0, "user", null, "seed marker")));
            var error = new AtomicReference<Throwable>();
            var done = new AtomicBoolean();
            var searcher = new Thread(() -> {
                try {
                    while (!done.get()) {
                        var hits = s.search(q("marker", Syntax.HYBRID, Filters.none(), 50));
                        assertFalse(hits.isEmpty());
                    }
                } catch (Throwable t) {
                    error.set(t);
                }
            });
            searcher.start();
            for (var i = 1; i <= 40; i++) {
                s.append(List.of(chunk("a", i, 0, "user", null, "marker " + i)));
                assertTrue(s.get("a:" + i + ".0").isPresent());
            }
            done.set(true);
            searcher.join();
            assertEquals(null, error.get());
            assertEquals(41, s.search(q("marker", Syntax.TEXT, Filters.none(), 100)).size());
        }
    }

    @Test
    void userChunksRankHighAndTheKindFilterFindsThem() {
        try (var s = store(null)) {
            var all = new ArrayList<Chunk>();
            for (var i = 1; i <= 30; i++) {
                all.add(chunk("a", i, 1, "mixed", null, ("TOOL_RESULT read the migration notes list sqlite and postgres and "
                    + "other storage options for step " + i + "\n").repeat(40)));
            }
            all.add(chunk("a", 31, 0, "user", "ask_user",
                "QUESTION Which database?\nANSWER sqlite, and do not use postgres for the migration"));
            s.append(all);
            var hits = s.search(q("sqlite postgres migration", Syntax.TEXT, Filters.none(), 5));
            assertTrue(ids(hits).subList(0, 3).contains("a:31.0"), ids(hits).toString());
            var users = s.search(q("sqlite", Syntax.TEXT, filters(Set.of(), OptionalInt.empty(), OptionalInt.empty(),
                Set.of("user"), Set.of()), 10));
            assertEquals(List.of("a:31.0"), ids(users));
        }
    }

    @Test
    void aFormAnswerIsFoundByAWordOfTheAnswerWithTheUserFilter() {
        var form = "FORM Setup\nQUESTION db Which database?\nCHOICES sqlite | postgres\nANSWER db sqlite\n"
            + "QUESTION note Anything else?\nANSWER note keep the zebrafish tables";
        try (var s = store(null)) {
            s.append(List.of(chunk("a", 1, 1, "mixed", null, "TOOL_RESULT read the zebrafish module list"),
                chunk("a", 2, 0, "user", "ask_user", form)));
            var hits = s.search(q("zebrafish", Syntax.TEXT, filters(Set.of(), OptionalInt.empty(), OptionalInt.empty(),
                Set.of("user"), Set.of()), 5));
            assertEquals(List.of("a:2.0"), ids(hits));
            assertTrue(hits.getFirst().snippet().contains("QUESTION note"), hits.getFirst().snippet());
        }
    }

    @Test
    void theUserBoostRaisesTheScoreOfUserChunks() {
        var text = "the answer is sqlite";
        var assistantFirst = List.of(chunk("a", 1, 0, "assistant", null, text), chunk("a", 2, 0, "user", null, text));
        try (var s = store(null)) {
            s.append(assistantFirst);
            assertEquals(List.of("a:2.0", "a:1.0"), ids(s.search(q("sqlite", Syntax.TEXT, Filters.none(), 5))));
        }
        var plain = LuceneContextStore.open(tmp.resolve("plain"), null, LuceneContextStore.Options.defaults()
            .withRetryInterval(Duration.ZERO).withUserBoost(1.0));
        try (plain) {
            plain.append(assistantFirst);
            assertEquals(List.of("a:1.0", "a:2.0"), ids(plain.search(q("sqlite", Syntax.TEXT, Filters.none(), 5))));
        }
        assertThrows(IllegalArgumentException.class, () -> LuceneContextStore.Options.defaults().withUserBoost(0));
    }

    @Test
    void theSnippetOfAShortUserChunkIsItsFullText() {
        var text = "ANSWER sqlite " + "and keep every table name as it is, ".repeat(15) + "end of the answer";
        assertTrue(text.length() > 400 && text.length() < 1000);
        try (var s = store(null)) {
            s.append(List.of(chunk("a", 1, 0, "user", "ask_user", text),
                chunk("a", 2, 0, "assistant", null, text)));
            var hits = s.search(q("sqlite", Syntax.TEXT, Filters.none(), 5));
            var user = hits.stream().filter(h -> h.chunk().kind().equals("user")).findFirst().orElseThrow();
            var other = hits.stream().filter(h -> h.chunk().kind().equals("assistant")).findFirst().orElseThrow();
            assertEquals(text, user.snippet());
            assertTrue(other.snippet().length() < text.length());
        }
    }
}
