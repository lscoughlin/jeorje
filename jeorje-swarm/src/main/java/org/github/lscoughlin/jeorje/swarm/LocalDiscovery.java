package org.github.lscoughlin.jeorje.swarm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional discovery on one host. Each instance writes a small file to the peers directory
 * (normally {@code ${HOME}/.jeorje/peers/}). Other instances read the files as extra WKA entries.
 */
public final class LocalDiscovery {
    private static final Logger log = LoggerFactory.getLogger(LocalDiscovery.class);

    /** The content of one discovery file. */
    public record Entry(String peerId, String address, String name) {}

    private final Path directory;

    public LocalDiscovery(Path directory) {
        this.directory = directory;
    }

    /** The default directory under the user directory. */
    public static Path defaultDirectory(Path userDir) {
        return userDir.resolve("peers");
    }

    private Path fileOf(String peerId) {
        return directory.resolve(peerId + ".json");
    }

    /** Writes the file of this instance, atomically. */
    public void announce(PeerRecord self) {
        try {
            Files.createDirectories(directory);
            var temp = Files.createTempFile(directory, ".peer-", ".tmp");
            Files.write(temp, Json.MAPPER.writeValueAsBytes(new Entry(self.peerId(), self.address(), self.name())));
            Files.move(temp, fileOf(self.peerId()), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Removes the file of this instance. */
    public void withdraw(String peerId) {
        try {
            Files.deleteIfExists(fileOf(peerId));
        } catch (IOException e) {
            log.atDebug().log(() -> "cannot remove the discovery file: " + e.getMessage());
        }
    }

    /** Reads the entries of other instances. A broken file is skipped. */
    public List<Entry> peers(String exceptPeerId) {
        var result = new ArrayList<Entry>();
        if (!Files.isDirectory(directory)) {
            return result;
        }
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (var file : files) {
                try {
                    var entry = Json.MAPPER.readValue(Files.readAllBytes(file), Entry.class);
                    if (entry.peerId() != null && entry.address() != null && !entry.peerId().equals(exceptPeerId)) {
                        result.add(entry);
                    }
                } catch (IOException | RuntimeException e) {
                    log.atDebug().log(() -> "skip discovery file " + file + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            log.atDebug().log(() -> "cannot read the peers directory: " + e.getMessage());
        }
        return result;
    }

    /** The addresses of the other instances. */
    public List<String> addresses(String exceptPeerId) {
        return peers(exceptPeerId).stream().map(Entry::address).toList();
    }
}
