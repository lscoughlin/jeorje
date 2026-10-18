package org.github.lscoughlin.jeorje.testkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** A temporary directory with helpers to write files. Close it to delete the directory. */
public final class TempWorkspace implements AutoCloseable {
    private final Path root;

    public TempWorkspace() {
        try {
            this.root = Files.createTempDirectory("jeorje-test-").toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path root() { return root; }

    public Path resolve(String relative) { return root.resolve(relative); }

    /** Writes a file and creates parent directories. Returns the path. */
    public Path write(String relative, String content) {
        try {
            var file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            return Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String read(String relative) {
        try {
            return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path mkdir(String relative) {
        try {
            return Files.createDirectories(root.resolve(relative));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean exists(String relative) { return Files.exists(root.resolve(relative)); }

    @Override
    public void close() {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
