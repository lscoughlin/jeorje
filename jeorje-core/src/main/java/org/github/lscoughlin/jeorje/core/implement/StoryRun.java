package org.github.lscoughlin.jeorje.core.implement;

import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.core.worktree.Worktree;

/** The mutable run record of one story. The orchestrator guards it with its lock. */
final class StoryRun {
    /** Why an attempt failed. */
    enum Failure { NONE, SYNC_ABORT, VERIFY, REVIEW, WORKER_BLOCKED, OTHER }

    /** What the worker signaled with its story tools. */
    enum Signal { NONE, DONE, BLOCKED }

    final String id;
    String title;
    StoryState state = StoryState.PENDING;
    int attempt;
    int failures;
    String worker = "";
    String workerName = "";
    String branch = "";
    String worktreePath = "";
    Worktree worktree;
    String result = "";
    String reason = "";
    /** The final completion summary of the last finished attempt. */
    String summary = "";
    String draft = "";
    List<String> files = new ArrayList<>();
    List<String> conflicts = new ArrayList<>();
    /** The text of the {@code Previous attempt} section for the next attempt, or an empty string. */
    String previousAttempt = "";
    String verify = "";
    Signal signal = Signal.NONE;
    String signalText = "";
    Failure failure = Failure.NONE;
    String failureText = "";
    boolean reviewed;
    boolean notMerged;
    String startError;
    /** The story that this story repairs, or null. */
    String repairOf;

    StoryRun(String id, String title) {
        this.id = id;
        this.title = title;
    }

    /** Clears the facts of one attempt before the next worker starts. */
    void resetAttempt() {
        signal = Signal.NONE;
        signalText = "";
        failure = Failure.NONE;
        failureText = "";
        reviewed = false;
        notMerged = false;
        startError = null;
        draft = "";
        conflicts = new ArrayList<>();
        worktree = null;
    }

    RunState.Entry entry() {
        return new RunState.Entry(id, state.label(), attempt, failures, worker, branch, worktreePath, result, reason,
            conflicts, previousAttempt);
    }
}
