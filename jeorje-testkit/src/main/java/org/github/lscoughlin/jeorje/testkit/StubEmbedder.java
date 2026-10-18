package org.github.lscoughlin.jeorje.testkit;

import org.github.lscoughlin.jeorje.core.port.Embedder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

/** A deterministic {@link Embedder}. The vector comes from a hash of the text. */
public final class StubEmbedder implements Embedder {
    public static final int DIMENSIONS = 384;

    @Override
    public String modelId() { return "stub-embedder"; }

    @Override
    public int dimensions() { return DIMENSIONS; }

    @Override
    public float[] embed(String text) {
        var seed = 1125899906842597L;
        for (var b : text.getBytes(StandardCharsets.UTF_8)) seed = 31 * seed + b;
        var random = new Random(seed);
        var vector = new float[DIMENSIONS];
        var norm = 0.0;
        for (var i = 0; i < DIMENSIONS; i++) {
            vector[i] = (float) random.nextGaussian();
            norm += vector[i] * vector[i];
        }
        var scale = (float) (1.0 / Math.sqrt(norm));
        for (var i = 0; i < DIMENSIONS; i++) vector[i] *= scale;
        return vector;
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}
