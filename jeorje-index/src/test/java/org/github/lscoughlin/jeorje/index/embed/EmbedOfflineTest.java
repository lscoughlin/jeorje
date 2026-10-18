package org.github.lscoughlin.jeorje.index.embed;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmbedOfflineTest {

    private static WordPieceTokenizer tokenizer() {
        return new WordPieceTokenizer(List.of(
                "[PAD]", "[UNK]", "[CLS]", "[SEP]", "hello", "world", "un", "##aff", "##able", ",", "!", "cafe"));
    }

    @Test
    void tokenizerSplitsWordPieces() {
        var ids = tokenizer().encode("Hello, unaffable World!", 256);
        assertArrayEquals(new int[] {2, 4, 9, 6, 7, 8, 5, 10, 3}, ids);
    }

    @Test
    void tokenizerHandlesUnknownAccentsAndTruncation() {
        var t = tokenizer();
        assertArrayEquals(new int[] {2, 11, 1, 3}, t.encode("Café zzz", 256));
        assertArrayEquals(new int[] {2, 4, 4, 3}, t.encode("hello hello hello hello", 4));
        assertArrayEquals(new int[] {2, 3}, t.encode("", 256));
    }

    @Test
    void poolingAveragesMaskedTokensAndNormalizes() {
        float[][][] hidden = {{{1, 0}, {3, 4}, {100, 100}}};
        var pooled = Pooling.meanPool(hidden, 0, new long[] {1, 1, 0});
        assertArrayEquals(new float[] {2, 2}, pooled, 1e-6f);
        var unit = Pooling.normalize(new float[] {3, 4});
        assertArrayEquals(new float[] {0.6f, 0.8f}, unit, 1e-6f);
        assertArrayEquals(new float[] {0, 0}, Pooling.normalize(new float[] {0, 0}));
    }

    @Test
    void embedBatchChunksBy64AndKeepsOrder() {
        var calls = new ArrayList<Integer>();
        OnnxEmbedder.ModelRunner runner = (ids, mask, types) -> {
            calls.add(ids.length);
            var out = new float[ids.length][ids[0].length][3];
            for (var r = 0; r < ids.length; r++) {
                for (var c = 0; c < ids[r].length; c++) {
                    out[r][c] = new float[] {ids[r][1], 1, 0};
                }
            }
            return out;
        };
        var embedder = new OnnxEmbedder(tokenizer(), runner, 3, () -> {});
        var texts = new ArrayList<String>();
        for (var i = 0; i < 130; i++) {
            texts.add(i % 2 == 0 ? "hello" : "world");
        }
        var vectors = embedder.embedBatch(texts);
        assertEquals(List.of(64, 64, 2), calls);
        assertEquals(130, vectors.size());
        for (var v : vectors) {
            assertEquals(1.0, Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]), 1e-5);
        }
        assertNotEquals(vectors.get(0)[0], vectors.get(1)[0]);
        assertEquals(vectors.get(0)[0], vectors.get(2)[0]);
        assertEquals(3, embedder.dimensions());
    }

    @Test
    void tryLoadReturnsEmptyForMissingDirectory(@TempDir Path tmp) {
        assertTrue(OnnxEmbedder.tryLoad(tmp.resolve("missing")).isEmpty());
        assertTrue(OnnxEmbedder.tryLoad(tmp).isEmpty());
    }

    @Test
    void downloaderChecksChecksum(@TempDir Path tmp) throws IOException {
        var src = tmp.resolve("src.txt");
        Files.writeString(src, "abc");
        var good = ModelDownloader.sha256(src);
        var dir = tmp.resolve("cache");
        var dl = new ModelDownloader(dir, List.of(new ModelDownloader.ModelFile("a.txt", src.toUri(), good)));
        assertFalse(dl.isComplete());
        dl.ensureDownloaded();
        assertTrue(dl.isComplete());
        assertEquals("abc", Files.readString(dir.resolve("a.txt")));

        var bad = new ModelDownloader(
                tmp.resolve("cache2"), List.of(new ModelDownloader.ModelFile("a.txt", src.toUri(), "00")));
        assertThrows(IOException.class, bad::ensureDownloaded);
        assertFalse(Files.exists(tmp.resolve("cache2").resolve("a.txt")));
    }
}
