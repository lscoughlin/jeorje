package org.github.lscoughlin.jeorje.core.worktree;

/**
 * The result of one git call.
 *
 * @param exit the exit code of the process
 * @param out the standard output
 * @param err the standard error
 */
public record GitResult(int exit, String out, String err) {
    /** True if the exit code is 0. */
    public boolean ok() { return exit == 0; }

    /** The output and the error text together. */
    public String text() { return (out + (out.isEmpty() || err.isEmpty() ? "" : "\n") + err).strip(); }

    /** The standard output without surrounding white space. */
    public String trimmed() { return out.strip(); }
}
