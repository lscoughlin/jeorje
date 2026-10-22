package org.github.lscoughlin.jeorje.swarm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/** Saves the {@code PeerId} of the instance in its state directory. */
public final class PeerIdStore {
    private PeerIdStore() {}

    /** Reads the saved id, or makes and saves a new random one. */
    public static String loadOrCreate(Path stateDir) {
        var file = stateDir.resolve("peer-id");
        try {
            if (Files.isRegularFile(file)) {
                var id = Files.readString(file, StandardCharsets.UTF_8).strip();
                if (!id.isEmpty()) {
                    return id;
                }
            }
            Files.createDirectories(stateDir);
            var id = UUID.randomUUID().toString();
            Files.writeString(file, id + "\n", StandardCharsets.UTF_8);
            return id;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the saved id when no live instance uses it. Otherwise makes a new random id, saves it, and
     * returns it. Two instances of one user on one machine share the state directory, so the saved id can be
     * live in another instance.
     *
     * @param liveIds the peer ids that other instances use now
     */
    public static String fresh(Path stateDir, Set<String> liveIds) {
        var saved = loadOrCreate(stateDir);
        if (!liveIds.contains(saved)) {
            return saved;
        }
        var id = UUID.randomUUID().toString();
        try {
            Files.writeString(stateDir.resolve("peer-id"), id + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return id;
    }
}
