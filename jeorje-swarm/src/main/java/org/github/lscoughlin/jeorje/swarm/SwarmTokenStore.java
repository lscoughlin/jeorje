package org.github.lscoughlin.jeorje.swarm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Resolves the swarm token. A token from the config wins. Without one, the token is the content of the file
 * {@code <stateDir>/swarm-token}. Jeorje writes a new random 32-byte token to that file when the file is
 * missing. All instances that share a state directory then share one token.
 */
public final class SwarmTokenStore {
    /** The name of the token file in the state directory. */
    public static final String FILE = "swarm-token";

    private SwarmTokenStore() {}

    /**
     * @param stateDir the state directory
     * @param configured the token of the config, may be null or blank
     * @return a non-empty token
     */
    public static String resolve(Path stateDir, String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        var file = stateDir.resolve(FILE);
        try {
            var existing = read(file);
            if (existing != null) {
                return existing;
            }
            Files.createDirectories(stateDir);
            var bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            var token = HexFormat.of().formatHex(bytes);
            try {
                Files.writeString(file, token + "\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
                restrict(file);
                return token;
            } catch (FileAlreadyExistsException e) {
                // Another instance wrote the file first. Use its token.
                for (var attempt = 0; attempt < 50; attempt++) {
                    var other = read(file);
                    if (other != null) {
                        return other;
                    }
                    Thread.sleep(20);
                }
                throw new IOException("the token file " + file + " stays empty");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        var token = Files.readString(file, StandardCharsets.UTF_8).strip();
        return token.isEmpty() ? null : token;
    }

    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // The file system has no POSIX permissions.
        }
    }
}
