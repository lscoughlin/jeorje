package org.github.lscoughlin.jeorje.core.subagent;

/** The runtime refuses to start a subagent. */
public final class SpawnException extends RuntimeException {
    public SpawnException(String message) {
        super(message);
    }
}
