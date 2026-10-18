package org.github.lscoughlin.jeorje.core.implement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.session.SessionManifest;
import tools.jackson.databind.json.JsonMapper;

/** Keeps the run state between two starts of Jeorje. */
public interface RunStateStore {
    Optional<RunState> load();

    void save(RunState state);

    /** Moves a file over another file in one step, so that a reader never sees a missing file. */
    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** A store that keeps nothing. */
    static RunStateStore memory() {
        return new RunStateStore() {
            private volatile RunState last;

            @Override public Optional<RunState> load() { return Optional.ofNullable(last); }

            @Override public void save(RunState state) { last = state; }
        };
    }

    /**
     * A store in the {@code implementState} field of the manifest file of the session. It reads the manifest on
     * each call, so it keeps the other fields of the manifest as they are. It saves nothing when the manifest
     * file does not exist.
     */
    static RunStateStore manifest(Path manifestFile) {
        var mapper = JsonMapper.builder().build();
        return new RunStateStore() {
            @Override
            public synchronized Optional<RunState> load() {
                if (!Files.isRegularFile(manifestFile)) return Optional.empty();
                try {
                    var node = SessionManifest.read(manifestFile).implementState();
                    if (node == null || node.isNull()) return Optional.empty();
                    return Optional.of(mapper.treeToValue(node, RunState.class));
                } catch (RuntimeException e) {
                    return Optional.empty();
                }
            }

            @Override
            public synchronized void save(RunState state) {
                if (!Files.isRegularFile(manifestFile)) {
                    throw new UncheckedIOException(new IOException("No manifest file: " + manifestFile));
                }
                SessionManifest.read(manifestFile).withImplementState(mapper.valueToTree(state))
                    .write(manifestFile);
            }
        };
    }

    /** A store in a JSON file next to the manifest of the session. */
    static RunStateStore file(Path file) {
        var mapper = JsonMapper.builder().build();
        return new RunStateStore() {
            @Override
            public synchronized Optional<RunState> load() {
                if (!Files.isRegularFile(file)) return Optional.empty();
                try {
                    return Optional.of(mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), RunState.class));
                } catch (IOException | RuntimeException e) {
                    return Optional.empty();
                }
            }

            @Override
            public synchronized void save(RunState state) {
                try {
                    var dir = file.toAbsolutePath().getParent();
                    Files.createDirectories(dir);
                    var tmp = Files.createTempFile(dir, ".implement-", ".tmp");
                    try {
                        Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(state),
                            StandardCharsets.UTF_8);
                        move(tmp, file);
                    } finally {
                        Files.deleteIfExists(tmp);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }
}
