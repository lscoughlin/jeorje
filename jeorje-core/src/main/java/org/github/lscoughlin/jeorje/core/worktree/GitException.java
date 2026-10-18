package org.github.lscoughlin.jeorje.core.worktree;

/** A git call that could not run: git is missing, the call timed out, or the version is too old. */
public class GitException extends RuntimeException {
    public GitException(String message) { super(message); }

    public GitException(String message, Throwable cause) { super(message, cause); }
}
