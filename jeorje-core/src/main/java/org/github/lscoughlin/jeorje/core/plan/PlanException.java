package org.github.lscoughlin.jeorje.core.plan;

/** A plan file has a structure error. */
public final class PlanException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int line;

    /** @param line the 1-based line of the error, or 0 if no line applies */
    public PlanException(int line, String message) {
        super(line > 0 ? "line " + line + ": " + message : message);
        this.line = line;
    }

    /** The 1-based line of the error, or 0. */
    public int line() { return line; }
}
