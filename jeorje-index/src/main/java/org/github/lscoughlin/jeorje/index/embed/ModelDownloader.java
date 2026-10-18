package org.github.lscoughlin.jeorje.index.embed;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Downloads model files one time into a cache directory and checks their SHA-256 values. */
public final class ModelDownloader {

    private static final Logger log = LoggerFactory.getLogger(ModelDownloader.class);

    /** One file of a model. */
    public record ModelFile(String name, URI uri, String sha256) {}

    public static final String MODEL_NAME = "model_quantized.onnx";
    public static final String VOCAB_NAME = "vocab.txt";

    /** The default files for all-MiniLM-L6-v2. */
    public static final List<ModelFile> MINILM_FILES = List.of(
            new ModelFile(
                    MODEL_NAME,
                    URI.create("https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx"),
                    "afdb6f1a0e45b715d0bb9b11772f032c399babd23bfc31fed1c170afc848bdb1"),
            new ModelFile(
                    VOCAB_NAME,
                    URI.create("https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt"),
                    "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3"));

    private final Path dir;
    private final List<ModelFile> files;

    public ModelDownloader(Path dir, List<ModelFile> files) {
        this.dir = dir;
        this.files = files;
    }

    public static Path defaultDir() {
        return Path.of(System.getProperty("user.home"), ".cache", "jeorje", "models", "all-MiniLM-L6-v2");
    }

    public Path dir() {
        return dir;
    }

    /** Returns true when every file exists and has the right checksum. */
    public boolean isComplete() {
        for (var f : files) {
            var path = dir.resolve(f.name());
            try {
                if (!Files.isRegularFile(path) || !sha256(path).equalsIgnoreCase(f.sha256())) {
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    /** Downloads each missing or damaged file. Throws if a download fails or a checksum is wrong. */
    public void ensureDownloaded() throws IOException {
        Files.createDirectories(dir);
        for (var f : files) {
            var target = dir.resolve(f.name());
            if (Files.isRegularFile(target) && sha256(target).equalsIgnoreCase(f.sha256())) {
                continue;
            }
            var tmp = Files.createTempFile(dir, f.name(), ".part");
            try {
                log.atInfo().log(() -> "Downloading " + f.uri());
                try (InputStream in = f.uri().toURL().openStream()) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                var actual = sha256(tmp);
                if (!actual.equalsIgnoreCase(f.sha256())) {
                    throw new IOException("Checksum mismatch for " + f.name() + ": got " + actual);
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    public static String sha256(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            var md = MessageDigest.getInstance("SHA-256");
            var buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
