package org.github.lscoughlin.jeorje.index.embed;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tag("live")
@EnabledIfEnvironmentVariable(named = "JEORJE_LIVE", matches = "1")
class OnnxEmbedderLiveTest {

    private static float dot(float[] a, float[] b) {
        var s = 0f;
        for (var i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    @Test
    void paraphrasesAreCloserThanUnrelatedSentences() {
        var embedder = OnnxEmbedder.tryLoad().orElseThrow();
        try (embedder) {
            var vs = embedder.embedBatch(java.util.List.of(
                    "A man is playing a guitar on stage.",
                    "Someone performs music with a guitar in front of an audience.",
                    "The quarterly tax filing deadline was extended."));
            assertEquals(384, vs.getFirst().length);
            assertEquals(1.0, dot(vs.get(0), vs.get(0)), 1e-3);
            assertTrue(dot(vs.get(0), vs.get(1)) > dot(vs.get(0), vs.get(2)));
        }
    }
}
