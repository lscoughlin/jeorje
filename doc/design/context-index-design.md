---
title: Context Index Design
description: Lucene schema, write path, query modes, embeddings, filters, versioning, and failure behavior of the context index.
---

# Context Index Design

A short overview is in [../context-index.md](../context-index.md). [context-and-compaction.md](context-and-compaction.md) describes how chunks are made. The index is in `jeorje-index`. It implements the `ContextStore` port.

## Port

```java
public interface ContextStore extends AutoCloseable {
    void append(List<Chunk> chunks);
    List<Hit> search(HistoryQuery query);
    Optional<Chunk> get(String chunkId);
    List<Chunk> window(String chunkId, int before, int after);
    void close();
}

public record HistoryQuery(String text, Syntax syntax, Filters filters, int limit) {}
public record Hit(Chunk chunk, float score, String snippet) {}
public enum Syntax { HYBRID, TEXT, RAW, VECTOR }
public record Filters(Set<String> agentIds, OptionalInt fromTurn, OptionalInt toTurn,
                      Optional<Instant> from, Optional<Instant> to,
                      Set<String> kinds, Set<String> toolNames) {}
```

## Layout on disk

The index of a session holds the chunks of all agents of the session, and every `search` covers all of them unless a filter narrows it. Each session has one Lucene index at `${CWD}/.jeorje/sessions/<sessionId>/index`. This path is in the project directory. See [configuration.md](configuration.md#directories). Subagents write to the same index through the single writer of the session. The `agentId` field identifies the source.

## Document schema

Each chunk is one Lucene document.

| Field | Type | Stored | Purpose |
|-------|------|--------|---------|
| `chunk_id` | StringField | yes | Unique id `<agentId>:<turn>.<part>` for lookup |
| `session_id` | StringField | yes | Session scope |
| `agent_id` | StringField | yes | Filter by agent |
| `turn` | IntPoint, StoredField, NumericDocValues | yes | Range filter and order |
| `part` | IntPoint, StoredField, NumericDocValues | yes | Position in the turn, and order inside a turn |
| `timestamp` | LongPoint, StoredField | yes | Time filter |
| `kind` | StringField | yes | `user`, `assistant`, `tool_call`, `tool_result`, `subagent_summary`, or `mixed` |
| `source_agent_id` | StringField | yes | For `subagent_summary`, the id of the subagent that wrote the summary. The field is absent when the chunk has no source agent. |
| `tool_name` | StringField | yes | Filter by tool. The field is absent when the chunk has no tool. |
| `text` | TextField (analyzed) | yes | BM25 search and snippet source |
| `vector` | KnnFloatVectorField, COSINE | no | Semantic search. The field is absent when the chunk has no vector. |
| `vector_state` | StringField | no | The value `pending` marks a chunk that has no vector. |

The commit metadata of the writer records `schema_version`, `embedding_model`, and `embedding_dims`. The schema version is 1.

## Analysis

The `text` field uses `CodeAnalyzer`, an analyzer that is suitable for code. It uses standard tokenization, a word-delimiter filter, and lowercase conversion. The word-delimiter filter splits `camelCase`, `snake_case`, numbers, and path separators. It also keeps the original token. A search for `context` and a search for `ContextStore` both match `ContextStore`. The analyzer keeps English stop words. Tool output and code have short words that carry meaning. The analyzer for documents flattens the token graph. The analyzer for queries does not.

## Write path

1. `append` receives the chunks of a finished turn.
2. `ScrubbingContextStore` wraps the index in the application. It removes secrets from the text of each chunk before the chunk enters the index. See [permissions-and-safety.md](permissions-and-safety.md#secrets).
3. If an embedder is configured, `append` embeds the chunk text on the calling thread, in batches of 64 rows. If a batch fails, it embeds each chunk of the batch alone.
4. The writer thread writes each document with `updateDocument` on the term `chunk_id`. A second append of the same chunk id replaces the first document, so an append is idempotent. A commit follows each append. The `SearcherManager` refreshes. The new turn is then searchable when `append` returns.
5. If embedding fails for a chunk, `append` does not fail. The index stores the chunk as text only with `vector_state=pending` and logs the failure. A background task tries the embedding again every 60 s and rewrites the chunk with its vector.

A chunk from a `recall` call holds only the query, the filters, and the ids of the returned chunks. It never holds the recalled text. Searches skip these chunks by default. A search can include them with the filter `toolNames=recall`. See [context-and-compaction.md](context-and-compaction.md#recall). When Jeorje rebuilds the index from `transcript.jsonl`, it applies the same rule: a `recall` result becomes a reference chunk, not a text chunk.

The history grows only by append. No API removes a chunk, and there is no checksum scan. The only rewrites of a chunk are the repeat of an append and the later embedding of a chunk with a pending vector. To delete a session index, delete the session directory.

## Query modes

A query with blank text returns no hits. A query limit of 0 or less means 10.

- **HYBRID (default).** Runs the TEXT leg and the VECTOR leg on the same reader snapshot. Each leg gives its top 50 candidates. Reciprocal rank fusion combines them: `score = sum(1 / (60 + rank))` for each leg, and the rank starts at 1. If no embedder is configured or the vector leg fails, HYBRID falls back to TEXT. Fused scores are not comparable with scores of other modes.
- **TEXT.** Jeorje escapes the user text and runs it as a BM25 query on `text`. A phrase in quotes becomes a phrase query. The words `AND`, `OR`, and `NOT` are plain words. A query that the parser cannot read gives an `IllegalArgumentException`.
- **RAW.** The Lucene classic query parser parses the query string against `text`. This mode is for expert use.
- **VECTOR.** Jeorje embeds the query and runs a KNN cosine query. If no embedder is available, this mode throws a `VectorUnavailableException`.

## Filters

Jeorje compiles filters to Lucene filter clauses (`IntPoint.newRangeQuery`, `LongPoint.newRangeQuery`, `TermInSetQuery`). The filters are agent ids, a turn range, a time range, kinds, and tool names. The text query gets a `FILTER` clause. The KNN query gets a pre-filter. Filtering therefore occurs before ranking. A strong match outside the filter cannot push out a weaker match inside the filter. Every search also excludes chunks with the tool name `recall`, unless the filter `toolNames` has `recall`. The methods `get` and `window` do not use filters. `window` returns chunks of the same agent in the order of turn and part.

## Embeddings

```java
public interface Embedder {
    String modelId();
    int dimensions();
    float[] embed(String text);
    List<float[]> embedBatch(List<String> texts);
}
```

- `OnnxEmbedder` runs `all-MiniLM-L6-v2` (quantized ONNX, 384 dimensions) on the CPU with ONNX Runtime. It uses WordPiece tokenization in pure JDK code, mean pooling, and L2 normalization. It truncates input that is longer than the model window (256 tokens) for the embedding. The full text stays in `text`. It runs at most 64 texts in one inference.
- `OnnxEmbedder.tryLoad()` downloads the model files one time into the default directory `~/.cache/jeorje/models/all-MiniLM-L6-v2` and checks their SHA-256 values. The CLI calls `tryLoad(Path)` with the directory of the config key `index.modelCache`. That call downloads nothing and returns empty when the files are missing. The index then runs in text-only mode and logs a warning. The config key `index.embeddings: false` also gives text-only mode.
- `StubEmbedder` is in `jeorje-testkit` and tests use it. It makes deterministic vectors from a hash of the text. Vector tests and hybrid tests then need no model files and no network.

## Versioning and drift

- If `schema_version` is missing or older and the index has documents, the index does one full reindex from the stored fields.
- If an embedder is configured and the stored `embedding_model` or `embedding_dims` is different, the index stops with an `EmbeddingDriftException`. The user runs `jeorje index rebuild`. The command opens the index with the rebuild option, then adds all chunks of the transcript again with new vectors. An index that has no stored model (a text-only index) takes the embedder with no error.

## Concurrency

Each session has one writer thread. Many searcher threads use `SearcherManager`. A search does not wait for a write. A commit and a refresh finish before `append` returns.

## Results

A `Hit` has the chunk (with `chunkId`, `agentId`, `turn`, `part`, `kind`, `toolName`, and the other values), a `score`, and a snippet of `text`. TEXT and RAW use the Lucene highlighter for the snippet. It marks matches with `[` and `]` and makes fragments of about 200 characters. VECTOR uses the first 200 characters of the text. HYBRID uses the highlighter for hits that the TEXT leg found and the first characters for other hits.

## Failure behavior

| Failure | Behavior |
|---------|----------|
| Corrupt index | The open fails. Jeorje can rebuild the index from the session transcript log. |
| Writer lock held | The open throws an `IllegalStateException`. Jeorje refuses a second writer. |
| Embedder load failure | Text-only mode. Jeorje logs a warning. |
| Embedding failure for a chunk | The chunk is stored as text only. A background task adds the vector later. |
| Disk full on commit | `append` throws. The agent shows an error and keeps the turn in memory. |
