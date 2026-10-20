package org.github.lscoughlin.jeorje.index.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.search.highlight.SimpleFragmenter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.util.BytesRef;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.Embedder;
import org.github.lscoughlin.jeorje.core.port.Filters;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;
import org.github.lscoughlin.jeorje.core.port.Hit;
import org.github.lscoughlin.jeorje.core.port.Syntax;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ContextStore} on Apache Lucene. One index holds all chunks of one session.
 * One writer thread does all writes. Searches use a {@link SearcherManager}.
 */
public final class LuceneContextStore implements ContextStore {
    private static final Logger log = LoggerFactory.getLogger(LuceneContextStore.class);

    /** The current schema version. An older index is reindexed on open. */
    public static final int SCHEMA_VERSION = 1;
    public static final String RECALL_TOOL = "recall";

    static final String CHUNK_ID = "chunk_id";
    static final String SESSION_ID = "session_id";
    static final String AGENT_ID = "agent_id";
    static final String TURN = "turn";
    static final String PART = "part";
    static final String TIMESTAMP = "timestamp";
    static final String KIND = "kind";
    static final String TOOL_NAME = "tool_name";
    static final String SOURCE_AGENT_ID = "source_agent_id";
    static final String TEXT = "text";
    static final String VECTOR = "vector";
    static final String VECTOR_STATE = "vector_state";
    static final String PENDING = "pending";
    static final String META_SCHEMA = "schema_version";
    static final String META_MODEL = "embedding_model";
    static final String META_DIMS = "embedding_dims";

    private static final int EMBED_BATCH = 64;
    private static final int LEG_SIZE = 50;
    private static final int RRF_K = 60;
    private static final int SNIPPET_CHARS = 200;
    private static final int USER_SNIPPET_CHARS = 1000;
    /** The default score factor for chunks of the kind {@code user}. */
    public static final double DEFAULT_USER_BOOST = 1.5;
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    /** Options for opening a store. */
    public record Options(Duration retryInterval, boolean rebuild, double userBoost) {
        public Options {
            if (!(userBoost > 0)) throw new IllegalArgumentException("userBoost must be above 0");
        }

        public static Options defaults() {
            return new Options(Duration.ofSeconds(60), false, DEFAULT_USER_BOOST);
        }

        public Options withRebuild(boolean value) {
            return new Options(retryInterval, value, userBoost);
        }

        public Options withRetryInterval(Duration value) {
            return new Options(value, rebuild, userBoost);
        }

        /** The factor for the score of a chunk of the kind {@code user} (1 means no boost). */
        public Options withUserBoost(double value) {
            return new Options(retryInterval, rebuild, value);
        }
    }

    private final Directory directory;
    private final IndexWriter writer;
    private final SearcherManager searchers;
    private final Embedder embedder;
    private final Options options;
    private final ExecutorService writerThread;
    private final ScheduledExecutorService retryThread;
    private final CodeAnalyzer queryAnalyzer = CodeAnalyzer.forQuery();
    private final Map<String, String> metadata = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Returns the index directory of a session: {@code <cwd>/.jeorje/sessions/<sessionId>/index}. */
    public static Path indexPath(Path cwd, String sessionId) {
        return cwd.resolve(".jeorje").resolve("sessions").resolve(sessionId).resolve("index");
    }

    /** Opens the index of a session under a project directory. The embedder may be null (text only). */
    public static LuceneContextStore open(Path cwd, String sessionId, Embedder embedder) {
        return open(indexPath(cwd, sessionId), embedder, Options.defaults());
    }

    /** Opens the index in a directory. The embedder may be null (text only). */
    public static LuceneContextStore open(Path indexDir, Embedder embedder, Options options) {
        return new LuceneContextStore(indexDir, embedder, options);
    }

    private LuceneContextStore(Path indexDir, Embedder embedder, Options options) {
        this.embedder = embedder;
        this.options = options;
        Directory dir = null;
        IndexWriter w = null;
        try {
            Files.createDirectories(indexDir);
            dir = FSDirectory.open(indexDir);
            var config = new IndexWriterConfig(CodeAnalyzer.forIndexing());
            try {
                w = new IndexWriter(dir, config);
            } catch (LockObtainFailedException e) {
                throw new IllegalStateException("The index is locked by another writer: " + indexDir, e);
            }
            this.directory = dir;
            this.writer = w;
            for (var entry : w.getLiveCommitData() == null ? List.<Map.Entry<String, String>>of()
                : toList(w.getLiveCommitData())) {
                metadata.put(entry.getKey(), entry.getValue());
            }
            prepare(options);
            this.searchers = new SearcherManager(w, null);
        } catch (IOException e) {
            closeQuietly(w);
            closeQuietly(dir);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            closeQuietly(w);
            closeQuietly(dir);
            throw e;
        }
        this.writerThread = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "jeorje-index-writer");
            t.setDaemon(true);
            return t;
        });
        this.retryThread = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "jeorje-index-retry");
            t.setDaemon(true);
            return t;
        });
        var interval = options.retryInterval().toMillis();
        if (embedder != null && interval > 0) {
            retryThread.scheduleWithFixedDelay(this::retryPendingQuietly, interval, interval, TimeUnit.MILLISECONDS);
        }
    }

    private static List<Map.Entry<String, String>> toList(Iterable<Map.Entry<String, String>> entries) {
        var list = new ArrayList<Map.Entry<String, String>>();
        for (var e : entries) list.add(Map.entry(e.getKey(), e.getValue()));
        return list;
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
            // closing after a failed open
        }
    }

    // ---- open, versioning, drift ----

    private void prepare(Options options) throws IOException {
        var hasDocs = writer.getDocStats().numDocs > 0;
        var schema = parseInt(metadata.get(META_SCHEMA), 0);
        var storedModel = metadata.getOrDefault(META_MODEL, "");
        var storedDims = parseInt(metadata.get(META_DIMS), 0);
        var drift = embedder != null && !storedModel.isBlank()
            && (!storedModel.equals(embedder.modelId()) || storedDims != embedder.dimensions());
        if (drift && !options.rebuild()) {
            throw new EmbeddingDriftException("The index was built with embedding model '" + storedModel + "' ("
                + storedDims + " dimensions). The embedder is '" + embedder.modelId() + "' ("
                + embedder.dimensions() + " dimensions). Run 'jeorje index rebuild'.");
        }
        var needReindex = hasDocs && (schema < SCHEMA_VERSION || drift);
        if (embedder != null) {
            metadata.put(META_MODEL, embedder.modelId());
            metadata.put(META_DIMS, Integer.toString(embedder.dimensions()));
        } else {
            metadata.putIfAbsent(META_MODEL, "");
            metadata.putIfAbsent(META_DIMS, "0");
        }
        metadata.put(META_SCHEMA, Integer.toString(SCHEMA_VERSION));
        if (needReindex) {
            log.atInfo().log(() -> "Reindexing the context index from stored fields.");
            reindexAll();
        }
        commit();
    }

    private void reindexAll() throws IOException {
        var chunks = readAllChunks();
        writer.deleteAll();
        var vectors = embedAll(chunks);
        for (var i = 0; i < chunks.size(); i++) {
            writer.updateDocument(new Term(CHUNK_ID, chunks.get(i).chunkId()), toDocument(chunks.get(i), vectors[i]));
        }
    }

    private List<Chunk> readAllChunks() throws IOException {
        var chunks = new ArrayList<Chunk>();
        try (var reader = DirectoryReader.open(writer)) {
            var stored = reader.storedFields();
            for (var i = 0; i < reader.maxDoc(); i++) {
                chunks.add(toChunk(stored.document(i)));
            }
        }
        return chunks;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void commit() throws IOException {
        writer.setLiveCommitData(new HashMap<>(metadata).entrySet(), false);
        writer.commit();
    }

    // ---- write path ----

    @Override
    public void append(List<Chunk> chunks) {
        if (chunks.isEmpty()) return;
        var vectors = embedAll(chunks);
        runOnWriter(() -> {
            for (var i = 0; i < chunks.size(); i++) {
                var chunk = chunks.get(i);
                writer.updateDocument(new Term(CHUNK_ID, chunk.chunkId()), toDocument(chunk, vectors[i]));
            }
            commit();
            searchers.maybeRefreshBlocking();
            return null;
        });
    }

    private <T> T runOnWriter(Callable<T> task) {
        ensureOpen();
        try {
            return writerThread.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while writing to the index", e);
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof IOException io) throw new UncheckedIOException(io);
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException(cause);
        }
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("The context store is closed");
    }

    /** Returns one vector for each chunk, or null where no vector is available. Never throws. */
    private float[][] embedAll(List<Chunk> chunks) {
        var result = new float[chunks.size()][];
        if (embedder == null) return result;
        for (var start = 0; start < chunks.size(); start += EMBED_BATCH) {
            var end = Math.min(start + EMBED_BATCH, chunks.size());
            var texts = new ArrayList<String>(end - start);
            for (var i = start; i < end; i++) texts.add(chunks.get(i).text());
            try {
                var batch = embedder.embedBatch(texts);
                if (batch.size() != texts.size()) throw new IllegalStateException("Wrong batch size");
                for (var i = start; i < end; i++) result[i] = validVector(batch.get(i - start));
            } catch (RuntimeException e) {
                log.atWarn().setCause(e).log(() -> "Batch embedding failed. Trying each chunk alone.");
                for (var i = start; i < end; i++) {
                    var text = chunks.get(i).text();
                    var id = chunks.get(i).chunkId();
                    try {
                        result[i] = validVector(embedder.embed(text));
                    } catch (RuntimeException single) {
                        log.atWarn().setCause(single).log(() -> "Embedding failed for chunk " + id + ". Text only.");
                    }
                }
            }
        }
        return result;
    }

    private float[] validVector(float[] v) {
        if (v == null || v.length != embedder.dimensions()) {
            throw new IllegalStateException("Embedding has the wrong dimension");
        }
        return v;
    }

    private static Document toDocument(Chunk c, float[] vector) {
        var doc = new Document();
        doc.add(new StringField(CHUNK_ID, c.chunkId(), Field.Store.YES));
        doc.add(new StringField(SESSION_ID, c.sessionId(), Field.Store.YES));
        doc.add(new StringField(AGENT_ID, c.agentId(), Field.Store.YES));
        doc.add(new IntPoint(TURN, c.turn()));
        doc.add(new StoredField(TURN, c.turn()));
        doc.add(new NumericDocValuesField(TURN, c.turn()));
        doc.add(new IntPoint(PART, c.part()));
        doc.add(new StoredField(PART, c.part()));
        doc.add(new NumericDocValuesField(PART, c.part()));
        var millis = c.timestamp().toEpochMilli();
        doc.add(new LongPoint(TIMESTAMP, millis));
        doc.add(new StoredField(TIMESTAMP, millis));
        doc.add(new StringField(KIND, c.kind(), Field.Store.YES));
        if (c.sourceAgentId() != null) {
            doc.add(new StringField(SOURCE_AGENT_ID, c.sourceAgentId(), Field.Store.YES));
        }
        if (c.toolName() != null) doc.add(new StringField(TOOL_NAME, c.toolName(), Field.Store.YES));
        doc.add(new TextField(TEXT, c.text(), Field.Store.YES));
        if (vector != null) {
            doc.add(new KnnFloatVectorField(VECTOR, vector, VectorSimilarityFunction.COSINE));
        } else {
            doc.add(new StringField(VECTOR_STATE, PENDING, Field.Store.NO));
        }
        return doc;
    }

    private static Chunk toChunk(Document d) {
        var tool = d.get(TOOL_NAME);
        var text = d.get(TEXT);
        return new Chunk(d.get(CHUNK_ID), nz(d.get(SESSION_ID)), nz(d.get(AGENT_ID)),
            parseInt(d.get(TURN), 0), parseInt(d.get(PART), 0),
            Instant.ofEpochMilli(parseLong(d.get(TIMESTAMP))), nz(d.get(KIND)), tool, text == null ? "" : text,
            d.get(SOURCE_AGENT_ID));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0L : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ---- background retry ----

    private void retryPendingQuietly() {
        try {
            retryPending();
        } catch (RuntimeException e) {
            log.atWarn().setCause(e).log(() -> "Background embedding retry failed.");
        }
    }

    /** Embeds the chunks that were indexed as text only. Returns the number that now have a vector. */
    public int retryPending() {
        if (embedder == null || closed.get()) return 0;
        var pending = new ArrayList<Chunk>();
        var searcher = acquire();
        try {
            var top = searcher.search(new TermQuery(new Term(VECTOR_STATE, PENDING)), Integer.MAX_VALUE - 1);
            var stored = searcher.storedFields();
            for (var sd : top.scoreDocs) pending.add(toChunk(stored.document(sd.doc)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            release(searcher);
        }
        if (pending.isEmpty()) return 0;
        var vectors = embedAll(pending);
        var fixed = 0;
        var toWrite = new ArrayList<Integer>();
        for (var i = 0; i < vectors.length; i++) if (vectors[i] != null) toWrite.add(i);
        if (toWrite.isEmpty()) return 0;
        runOnWriter(() -> {
            for (var i : toWrite) {
                var chunk = pending.get(i);
                writer.updateDocument(new Term(CHUNK_ID, chunk.chunkId()), toDocument(chunk, vectors[i]));
            }
            commit();
            searchers.maybeRefreshBlocking();
            return null;
        });
        fixed = toWrite.size();
        return fixed;
    }

    /** Returns the number of chunks that have no vector yet. */
    public int pendingCount() {
        var searcher = acquire();
        try {
            return searcher.count(new TermQuery(new Term(VECTOR_STATE, PENDING)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            release(searcher);
        }
    }

    // ---- search ----

    private IndexSearcher acquire() {
        ensureOpen();
        try {
            return searchers.acquire();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void release(IndexSearcher searcher) {
        try {
            searchers.release(searcher);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<Hit> search(HistoryQuery query) {
        var syntax = query.syntax() == null ? Syntax.HYBRID : query.syntax();
        var filters = query.filters() == null ? Filters.none() : query.filters();
        var limit = query.limit() <= 0 ? 10 : query.limit();
        var text = query.text() == null ? "" : query.text();
        if (syntax == Syntax.VECTOR && embedder == null) {
            throw new VectorUnavailableException("Vector search needs an embedder. None is configured.");
        }
        if (text.isBlank()) return List.of();
        var filter = compileFilter(filters);
        var searcher = acquire();
        try {
            return switch (syntax) {
                case TEXT -> textSearch(searcher, parseText(text), filter, limit);
                case RAW -> textSearch(searcher, parseRaw(text), filter, limit);
                case VECTOR -> vectorHits(searcher, vectorLeg(searcher, text, filter, limit), limit);
                case HYBRID -> hybrid(searcher, text, filter, limit);
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            release(searcher);
        }
    }

    private List<Hit> textSearch(IndexSearcher searcher, Query textQuery, Query filter, int limit) throws IOException {
        var docs = textLeg(searcher, textQuery, filter, Math.max(limit, LEG_SIZE));
        var hits = new ArrayList<Hit>(docs.length);
        var stored = searcher.storedFields();
        for (var sd : docs) {
            var chunk = toChunk(stored.document(sd.doc));
            hits.add(new Hit(chunk, (float) (sd.score * boost(chunk)), snippet(chunk, highlight(textQuery, chunk.text()))));
        }
        return top(hits, limit);
    }

    /** The score factor of a chunk: the user boost for the words of the user, 1 for all other chunks. */
    private double boost(Chunk chunk) {
        return "user".equals(chunk.kind()) ? options.userBoost() : 1.0;
    }

    /** A chunk of the kind {@code user} is short and important. Its snippet is the text, up to a limit. */
    private static String snippet(Chunk chunk, String snippet) {
        if (!"user".equals(chunk.kind())) return snippet;
        var text = chunk.text();
        return text.length() <= USER_SNIPPET_CHARS ? text : text.substring(0, USER_SNIPPET_CHARS);
    }

    private static List<Hit> top(List<Hit> hits, int limit) {
        var sorted = new ArrayList<>(hits);
        sorted.sort(Comparator.comparingDouble((Hit h) -> h.score()).reversed());
        return sorted.size() <= limit ? sorted : new ArrayList<>(sorted.subList(0, limit));
    }

    private ScoreDoc[] textLeg(IndexSearcher searcher, Query textQuery, Query filter, int n) throws IOException {
        var combined = new BooleanQuery.Builder().add(textQuery, Occur.MUST).add(filter, Occur.FILTER).build();
        return searcher.search(combined, n).scoreDocs;
    }

    private ScoreDoc[] vectorLeg(IndexSearcher searcher, String text, Query filter, int n) throws IOException {
        var vector = embedder.embed(text);
        if (vector == null || vector.length != embedder.dimensions()) {
            throw new IllegalStateException("Query embedding has the wrong dimension");
        }
        return searcher.search(new KnnFloatVectorQuery(VECTOR, vector, n, filter), n).scoreDocs;
    }

    private List<Hit> vectorHits(IndexSearcher searcher, ScoreDoc[] docs, int limit) throws IOException {
        var hits = new ArrayList<Hit>();
        var stored = searcher.storedFields();
        for (var sd : docs) {
            var chunk = toChunk(stored.document(sd.doc));
            hits.add(new Hit(chunk, (float) (sd.score * boost(chunk)), snippet(chunk, prefix(chunk.text()))));
        }
        return top(hits, limit);
    }

    private List<Hit> hybrid(IndexSearcher searcher, String text, Query filter, int limit) throws IOException {
        var textQuery = parseText(text);
        ScoreDoc[] vectorDocs = null;
        if (embedder != null) {
            try {
                vectorDocs = vectorLeg(searcher, text, filter, LEG_SIZE);
            } catch (RuntimeException e) {
                log.atWarn().setCause(e).log(() -> "Vector leg failed. HYBRID falls back to TEXT.");
            }
        }
        if (vectorDocs == null) return textSearch(searcher, textQuery, filter, limit);
        var textDocs = textLeg(searcher, textQuery, filter, LEG_SIZE);
        var fused = new HashMap<Integer, Double>();
        for (var rank = 0; rank < textDocs.length; rank++) {
            fused.merge(textDocs[rank].doc, 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        for (var rank = 0; rank < vectorDocs.length; rank++) {
            fused.merge(vectorDocs[rank].doc, 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        var textIds = new java.util.HashSet<Integer>();
        for (var sd : textDocs) textIds.add(sd.doc);
        var stored = searcher.storedFields();
        var chunks = new HashMap<Integer, Chunk>();
        for (var doc : fused.keySet()) {
            var chunk = toChunk(stored.document(doc));
            chunks.put(doc, chunk);
            fused.computeIfPresent(doc, (k, v) -> v * boost(chunk));
        }
        var ordered = new ArrayList<>(fused.entrySet());
        ordered.sort(Map.Entry.<Integer, Double>comparingByValue().reversed()
            .thenComparing(Map.Entry.comparingByKey()));
        var hits = new ArrayList<Hit>();
        for (var entry : ordered) {
            if (hits.size() >= limit) break;
            var chunk = chunks.get(entry.getKey());
            var found = textIds.contains(entry.getKey()) ? highlight(textQuery, chunk.text()) : prefix(chunk.text());
            hits.add(new Hit(chunk, entry.getValue().floatValue(), snippet(chunk, found)));
        }
        return hits;
    }

    // ---- query building ----

    private Query parseText(String raw) {
        try {
            var parser = new QueryParser(TEXT, queryAnalyzer);
            return parser.parse(escapeForParser(raw));
        } catch (ParseException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot parse the query: " + e.getMessage(), e);
        }
    }

    private Query parseRaw(String raw) {
        try {
            return new QueryParser(TEXT, queryAnalyzer).parse(raw);
        } catch (ParseException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot parse the query: " + e.getMessage(), e);
        }
    }

    /** Escapes user text for the classic parser. Quoted phrases stay phrases. */
    static String escapeForParser(String raw) {
        var out = new StringBuilder();
        var matcher = QUOTED.matcher(raw);
        var last = 0;
        while (matcher.find()) {
            appendWords(out, raw.substring(last, matcher.start()));
            var phrase = matcher.group(1).trim();
            if (!phrase.isEmpty()) out.append('"').append(QueryParser.escape(phrase)).append("\" ");
            last = matcher.end();
        }
        appendWords(out, raw.substring(last));
        return out.toString().trim();
    }

    private static void appendWords(StringBuilder out, String segment) {
        for (var word : segment.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            switch (word) {
                case "AND", "OR", "NOT" -> out.append('"').append(word).append("\" ");
                default -> out.append(QueryParser.escape(word)).append(' ');
            }
        }
    }

    private static Query compileFilter(Filters f) {
        var b = new BooleanQuery.Builder().add(new MatchAllDocsQuery(), Occur.FILTER);
        if (!f.agentIds().isEmpty()) b.add(termsQuery(AGENT_ID, f.agentIds()), Occur.FILTER);
        if (f.fromTurn().isPresent() || f.toTurn().isPresent()) {
            b.add(IntPoint.newRangeQuery(TURN, f.fromTurn().orElse(Integer.MIN_VALUE),
                f.toTurn().orElse(Integer.MAX_VALUE)), Occur.FILTER);
        }
        if (f.from().isPresent() || f.to().isPresent()) {
            b.add(LongPoint.newRangeQuery(TIMESTAMP, f.from().map(Instant::toEpochMilli).orElse(Long.MIN_VALUE),
                f.to().map(Instant::toEpochMilli).orElse(Long.MAX_VALUE)), Occur.FILTER);
        }
        if (!f.kinds().isEmpty()) b.add(termsQuery(KIND, f.kinds()), Occur.FILTER);
        if (!f.toolNames().isEmpty()) b.add(termsQuery(TOOL_NAME, f.toolNames()), Occur.FILTER);
        if (!f.toolNames().contains(RECALL_TOOL)) {
            b.add(new TermQuery(new Term(TOOL_NAME, RECALL_TOOL)), Occur.MUST_NOT);
        }
        return b.build();
    }

    private static Query termsQuery(String field, java.util.Collection<String> values) {
        var refs = new ArrayList<BytesRef>();
        for (var v : values) refs.add(new BytesRef(v));
        return new TermInSetQuery(field, refs);
    }

    // ---- snippets ----

    private String highlight(Query query, String text) {
        try {
            var formatter = new SimpleHTMLFormatter("[", "]");
            var highlighter = new Highlighter(formatter, new QueryScorer(query, TEXT));
            highlighter.setTextFragmenter(new SimpleFragmenter(SNIPPET_CHARS));
            var fragment = highlighter.getBestFragment(CodeAnalyzer.forIndexing(), TEXT, text);
            if (fragment != null && !fragment.isBlank()) return fragment;
        } catch (Exception | LinkageError e) {
            log.atDebug().log(() -> "Highlighting failed. Using a prefix: " + e.getMessage());
        }
        return prefix(text);
    }

    private static String prefix(String text) {
        return text.length() <= SNIPPET_CHARS ? text : text.substring(0, SNIPPET_CHARS);
    }

    // ---- get and window ----

    @Override
    public Optional<Chunk> get(String chunkId) {
        var searcher = acquire();
        try {
            var top = searcher.search(new TermQuery(new Term(CHUNK_ID, chunkId)), 1);
            if (top.scoreDocs.length == 0) return Optional.empty();
            return Optional.of(toChunk(searcher.storedFields().document(top.scoreDocs[0].doc)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            release(searcher);
        }
    }

    @Override
    public List<Chunk> window(String chunkId, int before, int after) {
        var center = get(chunkId);
        if (center.isEmpty()) return List.of();
        var c = center.get();
        var agent = new TermQuery(new Term(AGENT_ID, c.agentId()));
        var beforeQuery = new BooleanQuery.Builder().add(agent, Occur.FILTER)
            .add(new BooleanQuery.Builder()
                .add(IntPoint.newRangeQuery(TURN, Integer.MIN_VALUE, c.turn() - 1), Occur.SHOULD)
                .add(new BooleanQuery.Builder()
                    .add(IntPoint.newExactQuery(TURN, c.turn()), Occur.FILTER)
                    .add(IntPoint.newRangeQuery(PART, Integer.MIN_VALUE, c.part() - 1), Occur.FILTER).build(),
                    Occur.SHOULD)
                .build(), Occur.FILTER)
            .build();
        var afterQuery = new BooleanQuery.Builder().add(agent, Occur.FILTER)
            .add(new BooleanQuery.Builder()
                .add(IntPoint.newRangeQuery(TURN, c.turn() + 1, Integer.MAX_VALUE), Occur.SHOULD)
                .add(new BooleanQuery.Builder()
                    .add(IntPoint.newExactQuery(TURN, c.turn()), Occur.FILTER)
                    .add(IntPoint.newRangeQuery(PART, c.part() + 1, Integer.MAX_VALUE), Occur.FILTER).build(),
                    Occur.SHOULD)
                .build(), Occur.FILTER)
            .build();
        var result = new ArrayList<Chunk>();
        var searcher = acquire();
        try {
            if (before > 0) {
                var sort = new Sort(new SortField(TURN, SortField.Type.INT, true),
                    new SortField(PART, SortField.Type.INT, true));
                var docs = searcher.search(beforeQuery, before, sort).scoreDocs;
                var list = new ArrayList<Chunk>();
                for (var sd : docs) list.add(toChunk(searcher.storedFields().document(sd.doc)));
                Collections.reverse(list);
                result.addAll(list);
            }
            result.add(c);
            if (after > 0) {
                var sort = new Sort(new SortField(TURN, SortField.Type.INT), new SortField(PART, SortField.Type.INT));
                var docs = searcher.search(afterQuery, after, sort).scoreDocs;
                for (var sd : docs) result.add(toChunk(searcher.storedFields().document(sd.doc)));
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            release(searcher);
        }
    }

    // ---- lifecycle ----

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        retryThread.shutdownNow();
        writerThread.shutdown();
        try {
            writerThread.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeQuietly(searchers);
        closeQuietly(writer);
        closeQuietly(directory);
    }
}
