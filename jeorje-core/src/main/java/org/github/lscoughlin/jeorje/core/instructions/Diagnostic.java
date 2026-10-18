package org.github.lscoughlin.jeorje.core.instructions;

import java.nio.file.Path;

/** A problem or note that a loader records for an item. */
public record Diagnostic(Level level, Path file, String message) {

    /** Severity of a diagnostic. */
    public enum Level { INFO, WARNING, ERROR }

    public static Diagnostic warning(Path file, String message) {
        return new Diagnostic(Level.WARNING, file, message);
    }

    public static Diagnostic error(Path file, String message) {
        return new Diagnostic(Level.ERROR, file, message);
    }

    public static Diagnostic info(Path file, String message) {
        return new Diagnostic(Level.INFO, file, message);
    }
}
