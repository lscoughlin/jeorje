package org.github.lscoughlin.jeorje.core.worktree;

/** A worktree operation failed. */
public class WorktreeException extends RuntimeException {
    public WorktreeException(String message) { super(message); }

    public WorktreeException(String message, Throwable cause) { super(message, cause); }
}
