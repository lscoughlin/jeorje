---
title: Context Index
description: Overview of the Lucene index of the turn-chunked context history, with text, vector, and hybrid search.
---

# Context Index

Jeorje keeps a full index of its context history, chunked by turn. The index uses Apache Lucene. [design/context-index-design.md](design/context-index-design.md) gives the details.

- **One document for each chunk.** A document stores the chunk text, its turn identifier, and its position in the history. The history grows only by append. Jeorje indexes each turn one time, when the turn ends. A repeated append of the same chunk id replaces the chunk.
- **Text search.** Jeorje analyzes the chunk text and ranks results with BM25. Lucene query syntax (phrases, fields, booleans) is available for exact lookups.
- **Vector search.** Jeorje embeds each chunk on the local machine. It stores the vector in a cosine-similarity `KnnFloatVectorField`. A paraphrase that shares no words with a chunk still matches the chunk.
- **Hybrid search (default).** Jeorje combines the text ranking and the vector ranking with reciprocal rank fusion (RRF, `k = 60`). The query does not need a type. If no embedder is available, hybrid search uses text only. A fused score cannot be compared with a BM25 score or a cosine score.
- **Filtered queries.** Filters are agent, turn range, time range, chunk kind, and tool name. Lucene applies them before ranking. A strong match outside the filter cannot hide a weaker match inside the filter.
- **Local embeddings.** A MiniLM model (`all-MiniLM-L6-v2`, 384 dimensions) runs on the CPU with ONNX Runtime. No context text leaves the machine. Jeorje downloads the model one time and caches it. If the model is not available, the index stores text only. If the embedding of one chunk fails, a background task adds the vector later.
- **Index versioning.** The index records a schema version, the embedding model id, and the dimensions. A schema change causes a full reindex. A changed embedding model is rejected until the user rebuilds the index.
