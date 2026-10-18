package org.github.lscoughlin.jeorje.core.state;

import java.util.Objects;

/**
 * What an agent knows about a file after it read or wrote the file.
 *
 * @param modifiedMillis the modification time in milliseconds since the epoch
 * @param hash the SHA-256 hash of the content, as lower-case hex
 */
public record FileState(long modifiedMillis, String hash) {
    public FileState { Objects.requireNonNull(hash, "hash"); }
}
