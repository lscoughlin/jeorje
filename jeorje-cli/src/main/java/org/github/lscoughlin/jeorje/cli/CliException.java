package org.github.lscoughlin.jeorje.cli;

/** A user-facing failure. The message goes to standard error and the exit code is 2. */
public final class CliException extends RuntimeException {

    public CliException(String message) {
        super(message);
    }
}
