package org.github.lscoughlin.jeorje.index.store;

/** The embedding model or dimension of the index differs from the configured embedder. */
public final class EmbeddingDriftException extends IllegalStateException {
    public EmbeddingDriftException(String message) {
        super(message);
    }
}
