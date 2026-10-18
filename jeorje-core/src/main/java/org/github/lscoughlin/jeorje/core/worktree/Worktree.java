package org.github.lscoughlin.jeorje.core.worktree;

import java.nio.file.Path;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.subagent.SpawnMode;

/** One worktree of one subagent, with the facts that the completion sequence needs. */
public final class Worktree {
    /** The agent that owns the worktree. */
    public final AgentId agent;
    /** The parent agent, or null. */
    public final AgentId parent;
    public final String name;
    public final String group;
    public final Path path;
    public final String branch;
    /** The branch that the worktree started from and that the merge back goes to. */
    public final String sourceBranch;
    /** The directory where the source branch is checked out. */
    public final Path sourcePath;
    /** The commit where the worktree started. */
    public final String baseCommit;
    /** True if the base is a snapshot commit that the merge back must never include. */
    public final boolean snapshot;
    public final String task;
    public final SpawnMode mode;
    /** The verification command of the story, or null for the configured one. */
    public final String verify;

    private volatile boolean snapshotDropped;
    private volatile String mergeCommit;
    private volatile boolean merged;
    private volatile boolean squashed;
    private volatile boolean readyForMerge;

    Worktree(AgentId agent, AgentId parent, String name, String group, Path path, String branch, String sourceBranch,
             Path sourcePath, String baseCommit, boolean snapshot, String task, SpawnMode mode, String verify) {
        this.agent = agent;
        this.parent = parent;
        this.name = name;
        this.group = group;
        this.path = path;
        this.branch = branch;
        this.sourceBranch = sourceBranch;
        this.sourcePath = sourcePath;
        this.baseCommit = baseCommit;
        this.snapshot = snapshot;
        this.task = task == null ? "" : task;
        this.mode = mode == null ? SpawnMode.BACKGROUND : mode;
        this.verify = verify;
    }

    /** True if the sync already dropped the snapshot commit. */
    public boolean snapshotDropped() { return snapshotDropped; }

    void snapshotDropped(boolean value) { this.snapshotDropped = value; }

    /** The commit of the merge back, or null. For a squash it is the squash commit. */
    public String mergeCommit() { return mergeCommit; }

    /** True if the merge back is done. */
    public boolean merged() { return merged; }

    /** True if the merge back used the squash strategy. */
    public boolean squashed() { return squashed; }

    /** True if the branch is synced and verified and waits for a manual merge. */
    public boolean readyForMerge() { return readyForMerge; }

    void markReady(boolean value) { this.readyForMerge = value; }

    void markMerged(String commit, boolean squash) {
        this.mergeCommit = commit;
        this.merged = true;
        this.squashed = squash;
        this.readyForMerge = false;
    }

    /** The first line of the task. */
    public String taskLine() {
        var line = task.strip();
        var end = line.indexOf('\n');
        return end < 0 ? line : line.substring(0, end).strip();
    }

    @Override
    public String toString() { return name + " [" + branch + "]"; }
}
