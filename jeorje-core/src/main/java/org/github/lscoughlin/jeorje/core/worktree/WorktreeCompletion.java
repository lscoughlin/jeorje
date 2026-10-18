package org.github.lscoughlin.jeorje.core.worktree;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.subagent.AgentLifecycle;
import org.github.lscoughlin.jeorje.core.subagent.CompletionSteps;
import org.github.lscoughlin.jeorje.core.subagent.SpawnMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The completion sequence of a subagent with a worktree: commit, sync, resolve, verify, and merge back. It also
 * runs the manual merge for {@code /merge} and {@code worktree_merge}.
 */
public final class WorktreeCompletion implements CompletionSteps {
    private static final Logger log = LoggerFactory.getLogger(WorktreeCompletion.class);
    private static final int MAX_NUDGES = 2;
    private static final int MAX_VERIFY_ATTEMPTS = 3;

    /** Puts a message in the inbox of a parent agent. */
    @FunctionalInterface
    public interface ParentNotifier {
        void notify(AgentId parent, String message);
    }

    private final WorktreeManager manager;
    private final Verifier verifier;
    private final ParentNotifier notifier;
    private Duration retryInterval = Duration.ofSeconds(30);
    private IntConsumer beforeMerge = round -> {};

    public WorktreeCompletion(WorktreeManager manager, Verifier verifier, ParentNotifier notifier) {
        this.manager = manager;
        this.verifier = verifier;
        this.notifier = notifier == null ? (p, m) -> {} : notifier;
    }

    /** Sets the time between two merge tries in {@code background} mode. The default is 30 seconds. */
    public WorktreeCompletion retryInterval(Duration value) {
        this.retryInterval = value;
        return this;
    }

    /** Sets a hook that runs after each sync and before the merge back. Tests use it to move the source branch. */
    public WorktreeCompletion beforeMerge(IntConsumer hook) {
        this.beforeMerge = hook;
        return this;
    }

    @Override
    public Result run(Context ctx) throws Exception {
        var found = manager.byAgent(ctx.agent());
        if (found.isEmpty()) return Result.skipped();
        var w = found.get();
        var config = manager.config();
        try {
            manager.commitRemaining(w);
        } catch (RuntimeException e) {
            return Result.failed("Cannot commit the changes of the worktree: " + e.getMessage(), keepText(w));
        }
        var resolved = new ArrayList<String>();
        var maxRounds = Math.max(1, config.sync().maxRounds());
        for (var round = 1; round <= maxRounds + 1; round++) {
            if (ctx.cancel().isCancelled()) return Result.failed("cancelled", keepText(w));
            var finalRound = round > maxRounds;
            WorktreeManager.IntegrationLock held = null;
            try {
                if (finalRound) held = acquire(ctx, w);
                ctx.enter(AgentLifecycle.SYNCING);
                var failure = syncAndVerify(ctx, w, resolved);
                if (failure != null) return failure;
                if (config.mergeBack().equals("manual")) {
                    w.markReady(true);
                    return Result.ok("branch " + w.branch + " is synced and verified. It waits for a manual merge."
                        + " Run /merge " + w.name + " to merge it into " + w.sourceBranch + "." + resolvedText(resolved));
                }
                if (!finalRound) beforeMerge.accept(round);
                ctx.enter(AgentLifecycle.MERGING);
                if (held == null) held = acquire(ctx, w);
                if (!manager.sourceIsAncestor(w)) {
                    log.atDebug().log(() -> "The source moved during round of " + w.name + ". Starting a new round.");
                    continue;
                }
                var merged = manager.mergeBack(w, manager.strategy());
                switch (merged.kind()) {
                    case MERGED -> {
                        return Result.ok("branch " + w.branch + " merged into " + w.sourceBranch + " with strategy "
                            + manager.strategy() + " at commit " + merged.commit() + ". " + merged.message()
                            + resolvedText(resolved));
                    }
                    case ERROR -> {
                        return Result.failed("The merge back failed: " + merged.message(), keepText(w));
                    }
                    case DIRTY -> {
                        held.close();
                        held = null;
                        var outcome = handleDirty(ctx, w, merged);
                        if (outcome != null) return outcome;
                        // The tree is clean again, or the source moved: go around once more.
                    }
                }
            } finally {
                if (held != null) held.close();
            }
        }
        return Result.failed("The merge back did not finish after " + (maxRounds + 1) + " rounds.", keepText(w));
    }

    private WorktreeManager.IntegrationLock acquire(Context ctx, Worktree w) throws Exception {
        try (var wait = ctx.internalWait(null, "integration lock of " + w.sourceBranch)) {
            return manager.lockIntegration(w, ctx.cancel());
        }
    }

    /** Handles a dirty parent tree. Returns a result to end the sequence, or null to try the merge again. */
    private Result handleDirty(Context ctx, Worktree w, WorktreeManager.MergeResult dirty) throws Exception {
        var files = String.join(", ", dirty.files());
        var notMerged = "completed (not merged): the parent tree has uncommitted changes in "
            + (files.isEmpty() ? "files that the merge touches" : files) + ". Branch " + w.branch
            + " is kept. Commit or stash these files, then run /merge " + w.name + ".";
        if (w.mode == SpawnMode.WAIT) return Result.ok(notMerged);
        var wait = Math.max(0, manager.config().mergeWaitSeconds());
        if (w.parent != null) {
            notifier.notify(w.parent, "The merge of " + w.branch + " into " + w.sourceBranch
                + " is blocked by uncommitted changes in " + files + ". Commit or stash them. Jeorje tries again for "
                + wait + " seconds.");
        }
        var deadline = System.nanoTime() + Duration.ofSeconds(wait).toNanos();
        while (System.nanoTime() < deadline) {
            if (ctx.cancel().isCancelled()) return Result.failed("cancelled", keepText(w));
            Thread.sleep(Math.max(1, Math.min(retryInterval.toMillis(),
                Math.max(1, (deadline - System.nanoTime()) / 1_000_000))));
            try (var lock = acquire(ctx, w)) {
                if (!manager.sourceIsAncestor(w)) return null;
                var again = manager.mergeBack(w, manager.strategy());
                switch (again.kind()) {
                    case MERGED -> {
                        return Result.ok("branch " + w.branch + " merged into " + w.sourceBranch + " at commit "
                            + again.commit() + " after the parent tree was clean. " + again.message());
                    }
                    case ERROR -> {
                        return Result.failed("The merge back failed: " + again.message(), keepText(w));
                    }
                    case DIRTY -> files = String.join(", ", again.files());
                }
            }
        }
        return Result.ok(notMerged);
    }

    /** Syncs, resolves, and verifies. Returns null on success, or the failed result. */
    private Result syncAndVerify(Context ctx, Worktree w, List<String> resolved) throws Exception {
        var sync = manager.sync(w);
        switch (sync.kind()) {
            case ERROR -> {
                return Result.failed("The sync failed: " + sync.output(), keepText(w));
            }
            case CONFLICT -> {
                resolved.addAll(sync.conflicts());
                ctx.enter(AgentLifecycle.RESOLVING);
                var message = "Your branch " + w.branch + " conflicts with " + w.sourceBranch + ".\n"
                    + "Conflicting files:\n" + String.join("\n", sync.conflicts()) + "\n\n"
                    + "git status:\n" + sync.status() + "\n\n"
                    + "New commits from " + w.sourceBranch + ":\n" + String.join("\n", sync.incoming()) + "\n\n"
                    + "Edit the files in your worktree and remove all conflict markers. Then call sync_done."
                    + " Call sync_abort if you cannot resolve the conflict.";
                var failure = awaitResolution(ctx, w, message);
                if (failure != null) return failure;
                ctx.enter(AgentLifecycle.SYNCING);
            }
            case CLEAN -> { }
        }
        var command = w.verify != null && !w.verify.isBlank() ? w.verify : manager.config().sync().verify();
        if (command == null || command.isBlank()) return null;
        for (var attempt = 1; attempt <= MAX_VERIFY_ATTEMPTS; attempt++) {
            var result = verifier.verify(w.path, command);
            if (result.ok()) return null;
            if (attempt == MAX_VERIFY_ATTEMPTS) {
                return Result.failed("The verification failed after the sync: " + tail(result.output()), keepText(w));
            }
            ctx.enter(AgentLifecycle.RESOLVING);
            var failure = awaitResolution(ctx, w, "The verification command failed after the sync with "
                + w.sourceBranch + ".\nCommand: " + command + "\nOutput:\n" + tail(result.output())
                + "\n\nFix the problem in your worktree. Then call sync_done. Call sync_abort if you cannot fix it.");
            if (failure != null) return failure;
            ctx.enter(AgentLifecycle.SYNCING);
        }
        return null;
    }

    /** Resumes the agent until it calls {@code sync_done} or {@code sync_abort}. Returns null after sync_done. */
    private Result awaitResolution(Context ctx, Worktree w, String message) {
        var text = message;
        var budget = manager.config().sync().maxModelCalls();
        var remaining = budget;
        for (var nudge = 0; nudge <= MAX_NUDGES; nudge++) {
            var used = ctx.resumeWork(text, remaining);
            remaining -= used;
            var signal = manager.takeSignal(ctx.agent());
            if (signal.isPresent()) {
                if (signal.get().resolution() == WorktreeManager.Resolution.DONE) return null;
                manager.syncAbort(w);
                return Result.failed("sync_abort: " + signal.get().text(), keepText(w)
                    + (manager.unmerged(w).isEmpty() ? "" : " Conflicts: " + String.join(", ", manager.unmerged(w))));
            }
            if (remaining <= 0) {
                var files = manager.unmerged(w);
                manager.syncAbort(w);
                return Result.failed("The resolution used all " + budget + " model calls of the round and did not"
                    + " finish." + (files.isEmpty() ? "" : " Unresolved conflicts: " + String.join(", ", files)),
                    keepText(w));
            }
            if (ctx.cancel().isCancelled()) break;
            text = "You ended the turn without sync_done or sync_abort. The sync is not finished. Call sync_done"
                + " after you fix the files, or sync_abort with a reason.";
        }
        var files = manager.unmerged(w);
        manager.syncAbort(w);
        return Result.failed("The resolution did not finish." + (files.isEmpty() ? "" : " Unresolved conflicts: "
            + String.join(", ", files)), keepText(w));
    }

    private static String keepText(Worktree w) {
        return "branch " + w.branch + " and worktree " + w.path + " are kept. The source branch " + w.sourceBranch
            + " is unchanged.";
    }

    private static String resolvedText(List<String> resolved) {
        return resolved.isEmpty() ? "" : " Conflicts resolved during the sync: " + String.join(", ", resolved) + ".";
    }

    private static String tail(String text) {
        return text.length() > 4000 ? "..." + text.substring(text.length() - 4000) : text;
    }

    // ---- manual merge ----

    /** The result of a manual merge. */
    public record ManualResult(boolean merged, String message, String note) {}

    /**
     * Runs the completion sequence for a branch that no agent works on: sync from the source (again, if it moved),
     * verify, and merge back. A conflict cannot be resolved here, so the sync stops and restores the branch.
     *
     * @param strategy {@code merge}, {@code squash}, {@code rebase}, or null for the configured one
     */
    public ManualResult manualMerge(Worktree w, String strategy) throws InterruptedException {
        var use = strategy == null || strategy.isBlank() ? manager.strategy() : strategy;
        if (!List.of("merge", "squash", "rebase").contains(use)) {
            return new ManualResult(false, "Unknown strategy '" + use + "'. Use merge, squash, or rebase.", null);
        }
        if (w.merged()) return new ManualResult(false, "Branch " + w.branch + " is already merged.", null);
        if (manager.inProgress(w) != WorktreeManager.Progress.NONE) {
            return new ManualResult(false, "A sync is in progress in " + w.path
                + ". Resolve the conflicts, stage the files, and finish the merge or rebase first.", null);
        }
        try (var lock = manager.lockIntegration(w, null)) {
            manager.commitRemaining(w);
            if (!manager.sourceIsAncestor(w) || (w.snapshot && !w.snapshotDropped())) {
                var sync = manager.sync(w);
                switch (sync.kind()) {
                    case ERROR -> {
                        return new ManualResult(false, "The sync failed: " + sync.output(), null);
                    }
                    case CONFLICT -> {
                        manager.syncAbort(w);
                        return new ManualResult(false, "The sync has conflicts in " + String.join(", ", sync.conflicts())
                            + ". Resolve them in " + w.path + " (git merge " + w.sourceBranch
                            + "), commit, and run /merge again, or start a repair subagent.", null);
                    }
                    case CLEAN -> { }
                }
            }
            var command = w.verify != null && !w.verify.isBlank() ? w.verify : manager.config().sync().verify();
            if (command != null && !command.isBlank()) {
                var check = verifier.verify(w.path, command);
                if (!check.ok()) {
                    return new ManualResult(false, "The verification failed after the sync: " + tail(check.output()), null);
                }
            }
            var merged = manager.mergeBack(w, use);
            switch (merged.kind()) {
                case MERGED -> {
                    var cleanup = manager.cleanup(w, true);
                    return new ManualResult(true, merged.message() + (cleanup.removed() ? " The worktree is removed." : ""),
                        cleanup.note());
                }
                case DIRTY -> {
                    return new ManualResult(false, "The parent tree has uncommitted changes in "
                        + String.join(", ", merged.files()) + ". Commit or stash them, then run /merge again.", null);
                }
                default -> {
                    return new ManualResult(false, "The merge back failed: " + merged.message(), null);
                }
            }
        }
    }
}
