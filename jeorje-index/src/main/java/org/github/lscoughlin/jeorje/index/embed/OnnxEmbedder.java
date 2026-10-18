package org.github.lscoughlin.jeorje.index.embed;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.port.Embedder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Embedder for all-MiniLM-L6-v2 that runs on the CPU with ONNX Runtime. */
public final class OnnxEmbedder implements Embedder, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OnnxEmbedder.class);

    public static final String MODEL_ID = "all-MiniLM-L6-v2";
    public static final int DIMENSIONS = 384;
    public static final int MAX_TOKENS = 256;
    public static final int BATCH_SIZE = 64;

    /** Runs the model. The result has the layout [batch][seq][dim]. */
    @FunctionalInterface
    public interface ModelRunner {
        float[][][] run(long[][] ids, long[][] mask, long[][] types) throws Exception;
    }

    private final WordPieceTokenizer tokenizer;
    private final ModelRunner runner;
    private final AutoCloseable resource;
    private final int dimensions;

    OnnxEmbedder(WordPieceTokenizer tokenizer, ModelRunner runner, int dimensions, AutoCloseable resource) {
        this.tokenizer = tokenizer;
        this.runner = runner;
        this.dimensions = dimensions;
        this.resource = resource;
    }

    /** Loads from the default cache directory and downloads the files if they are missing. */
    public static Optional<OnnxEmbedder> tryLoad() {
        return tryLoad(ModelDownloader.defaultDir(), true);
    }

    /** Loads from a directory without any download. Returns empty when the files are missing. */
    public static Optional<OnnxEmbedder> tryLoad(Path dir) {
        return tryLoad(dir, false);
    }

    public static Optional<OnnxEmbedder> tryLoad(Path dir, boolean download) {
        try {
            var downloader = new ModelDownloader(dir, ModelDownloader.MINILM_FILES);
            if (download) {
                downloader.ensureDownloaded();
            }
            var modelPath = dir.resolve(ModelDownloader.MODEL_NAME);
            var vocabPath = dir.resolve(ModelDownloader.VOCAB_NAME);
            if (!Files.isRegularFile(modelPath) || !Files.isRegularFile(vocabPath)) {
                throw new IllegalStateException("Model files are missing in " + dir);
            }
            var tokenizer = WordPieceTokenizer.load(vocabPath);
            var env = OrtEnvironment.getEnvironment();
            var session = env.createSession(modelPath.toString(), new OrtSession.SessionOptions());
            ModelRunner runner = (ids, mask, types) -> {
                try (var idT = OnnxTensor.createTensor(env, ids);
                        var maskT = OnnxTensor.createTensor(env, mask);
                        var typeT = OnnxTensor.createTensor(env, types);
                        var result = session.run(Map.of(
                                "input_ids", idT, "attention_mask", maskT, "token_type_ids", typeT))) {
                    return (float[][][]) result.get(0).getValue();
                }
            };
            return Optional.of(new OnnxEmbedder(tokenizer, runner, DIMENSIONS, session));
        } catch (Throwable e) {
            log.atWarn().log(() -> "Embedding model is not available. Search runs in text-only mode. Cause: " + e);
            return Optional.empty();
        }
    }

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text)).getFirst();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        var out = new ArrayList<float[]>(texts.size());
        for (var from = 0; from < texts.size(); from += BATCH_SIZE) {
            var chunk = texts.subList(from, Math.min(texts.size(), from + BATCH_SIZE));
            out.addAll(runChunk(chunk));
        }
        return out;
    }

    private List<float[]> runChunk(List<String> chunk) {
        var encoded = new ArrayList<int[]>(chunk.size());
        var width = 0;
        for (var text : chunk) {
            var ids = tokenizer.encode(text, MAX_TOKENS);
            encoded.add(ids);
            width = Math.max(width, ids.length);
        }
        var n = chunk.size();
        var ids = new long[n][width];
        var mask = new long[n][width];
        var types = new long[n][width];
        var pad = tokenizer.padId();
        for (var r = 0; r < n; r++) {
            var row = encoded.get(r);
            for (var c = 0; c < width; c++) {
                if (c < row.length) {
                    ids[r][c] = row[c];
                    mask[r][c] = 1;
                } else {
                    ids[r][c] = pad;
                }
            }
        }
        float[][][] hidden;
        try {
            hidden = runner.run(ids, mask, types);
        } catch (Exception e) {
            throw new IllegalStateException("Embedding inference failed", e);
        }
        var out = new ArrayList<float[]>(n);
        for (var r = 0; r < n; r++) {
            out.add(Pooling.normalize(Pooling.meanPool(hidden, r, mask[r])));
        }
        return out;
    }

    @Override
    public void close() {
        try {
            resource.close();
        } catch (Exception e) {
            log.atDebug().log(() -> "Close failed: " + e);
        }
    }
}
