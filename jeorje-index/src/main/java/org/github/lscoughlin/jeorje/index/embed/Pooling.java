package org.github.lscoughlin.jeorje.index.embed;

/** Mean pooling and L2 normalization for model output. */
public final class Pooling {

    private Pooling() {}

    /**
     * Averages the token vectors of one row. The hidden array has the layout [batch][seq][dim]. The mask has one entry for each token.
     */
    public static float[] meanPool(float[][][] hidden, int row, long[] mask) {
        var seq = hidden[row];
        var dim = seq.length == 0 ? 0 : seq[0].length;
        var out = new float[dim];
        var count = 0;
        for (var t = 0; t < seq.length && t < mask.length; t++) {
            if (mask[t] == 0) {
                continue;
            }
            count++;
            var token = seq[t];
            for (var d = 0; d < dim; d++) {
                out[d] += token[d];
            }
        }
        if (count > 0) {
            for (var d = 0; d < dim; d++) {
                out[d] /= count;
            }
        }
        return out;
    }

    /** Scales the vector in place to unit length. A zero vector stays zero. */
    public static float[] normalize(float[] v) {
        var sum = 0.0;
        for (var x : v) {
            sum += (double) x * x;
        }
        var norm = Math.sqrt(sum);
        if (norm > 0) {
            for (var i = 0; i < v.length; i++) {
                v[i] = (float) (v[i] / norm);
            }
        }
        return v;
    }
}
