package org.github.lscoughlin.jeorje.core.port;

import java.util.List;

/** Port that converts text to a vector of fixed size. */
public interface Embedder {
    String modelId();

    int dimensions();

    /** Returns a vector of length {@link #dimensions()}. */
    float[] embed(String text);

    /** Returns one vector for each text, in the same order. */
    List<float[]> embedBatch(List<String> texts);
}
