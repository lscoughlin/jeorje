package org.github.lscoughlin.jeorje.index.store;

/** A vector search was requested and no embedder is available. */
public final class VectorUnavailableException extends IllegalStateException {
    public VectorUnavailableException(String message) {
        super(message);
    }
}
