package org.github.lscoughlin.jeorje.core.worktree;

import static org.github.lscoughlin.jeorje.core.worktree.WorktreeTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.subagent.AgentLifecycle;
import org.github.lscoughlin.jeorje.core.subagent.CompletionSteps;
import org.github.lscoughlin.jeorje.core.subagent.SpawnMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreeCompletionTest {
    @TempDir Path tmp;

    private Path root;
    private WorktreeManager manager;
    private final List<String> parentMessages = new ArrayList<>();
    private Verifier verifier = (dir, cmd) -> new Verifier.Result(true, "");

    private void setup(JeorjeConfig.Worktree cfg) throws IOException {
        root = repo(tmp);
        manager = manager(root, cfg, new ArrayList<>());
    }

    private Worktree worktree(String id, String name, SpawnMode mode) {
        return manager.create(request(root, id, name, mode)).orElseThrow();
    }

    private WorktreeCompletion steps() {
        return new WorktreeCompletion(manager, (d, c) -> verifier.verify(d, c), (p, m) -> parentMessages.add(m))
            .retryInterval(Duration.ofMillis(50));
    }

    private CompletionSteps.Result run(Worktree w, FakeContext ctx) throws Exception {
        return steps().run(ctx);
    }

    /** The agent resolves the conflict by writing the file, then calls sync_done like the tool does. */
    private void resolver(FakeContext ctx, Worktree w, String file, String text) {
        ctx.resume = message -> {
            try {
                write(w.path, file, text);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            var done = manager.syncDone(w);
            assertEquals(WorktreeManager.DoneResult.Kind.DONE, done.kind(), done.message());
            manager.signal(w.agent, WorktreeManager.Resolution.DONE, "resolved");
        };
    }

    private static String branches(Path dir) { return g(dir, "branch", "--list", "jeorje/*"); }

    // ---- the three strategies ----

    @Test
    void mergeStrategyMergesBackAndCleansUp() throws Exception {
        setup(config("merge", "head"));
        var w = worktree("a1", "alpha", SpawnMode.WAIT);
        write(w.path, "alpha.txt", "alpha\n");
        commit(root, "other.txt", "other\n", "parent moves on");
        var ctx = new FakeContext(w);
        var result = run(w, ctx);
        assertTrue(result.success(), result.failure());
        assertEquals(List.of(AgentLifecycle.SYNCING, AgentLifecycle.MERGING), ctx.states);
        assertTrue(result.branch().contains("merged into main"));
        assertTrue(Files.exists(root.resolve("alpha.txt")));
        assertTrue(Files.exists(root.resolve("other.txt")));
        assertFalse(g(root, "log", "--merges", "--format=%s").isEmpty(), "a merge commit exists");
        assertTrue(w.merged());
        assertEquals("merged", manager.state(w));
        var cleanup = manager.cleanup(w, true);
        assertTrue(cleanup.removed());
        assertTrue(cleanup.branchDeleted());
        assertTrue(cleanup.note().startsWith("worktree removed: alpha"));
        assertTrue(cleanup.note().contains("jeorje/g1/alpha"));
        assertFalse(Files.exists(w.path));
        assertEquals("", branches(root));
        assertTrue(manager.rows().isEmpty());
    }

    @Test
    void squashStrategyMakesOneCommitAndCleanupUsesForcedDelete() throws Exception {
        setup(config("squash", "head"));
        var w = worktree("a1", "sq", SpawnMode.WAIT);
        write(w.path, "one.txt", "1\n");
        g(w.path, "add", "-A");
        g(w.path, "commit", "-q", "-m", "first");
        write(w.path, "two.txt", "2\n");
        var result = run(w, new FakeContext(w));
        assertTrue(result.success(), result.failure());
        assertEquals("", g(root, "log", "--merges", "--format=%s"));
        assertEquals("jeorje: sq", g(root, "log", "-1", "--format=%s"));
        assertEquals("2", g(root, "rev-list", "--count", "HEAD"), "initial commit plus one squash commit");
        // git does not see the branch as merged
        var plain = GIT.run(root, "branch", "-d", w.branch);
        // plain -d refuses; the manager must use -D after checking the squash commit
        assertFalse(plain.ok(), "git branch -d refuses after a squash merge");
        var cleanup = manager.cleanup(w, true);
        assertTrue(cleanup.removed());
        assertTrue(cleanup.branchDeleted());
        assertEquals("", branches(root));
    }

    @Test
    void squashCleanupKeepsBranchWhenSquashCommitIsMissing() throws Exception {
        setup(config("squash", "head"));
        var w = worktree("a1", "sq", SpawnMode.WAIT);
        write(w.path, "one.txt", "1\n");
        assertTrue(run(w, new FakeContext(w)).success());
        // the squash commit leaves the source branch
        g(root, "reset", "-q", "--hard", "HEAD~1");
        var cleanup = manager.cleanup(w, true);
        assertTrue(cleanup.removed());
        assertFalse(cleanup.branchDeleted());
        assertEquals(w.branch, branches(root).replace("*", "").strip());
    }

    @Test
    void rebaseStrategyKeepsHistoryLinear() throws Exception {
        setup(config("rebase", "head"));
        var w = worktree("a1", "rb", SpawnMode.WAIT);
        write(w.path, "rb.txt", "rb\n");
        commit(root, "other.txt", "other\n", "parent moves on");
        var result = run(w, new FakeContext(w));
        assertTrue(result.success(), result.failure());
        assertEquals("", g(root, "log", "--merges", "--format=%s"));
        assertEquals(g(root, "rev-parse", "HEAD"), g(w.path, "rev-parse", "HEAD"), "a fast-forward");
        assertTrue(Files.exists(root.resolve("rb.txt")) && Files.exists(root.resolve("other.txt")));
        assertTrue(manager.cleanup(w, true).branchDeleted(), "git branch -d works after rebase and fast-forward");
    }

    // ---- sync and resolve ----

    @Test
    void twoSubagentsChangeTheSameFile() throws Exception {
        setup(config("merge", "head"));
        var w1 = worktree("a1", "one", SpawnMode.BACKGROUND);
        var w2 = worktree("a2", "two", SpawnMode.BACKGROUND);
        write(w1.path, "src/App.txt", "one\nW1\nthree\n");
        write(w2.path, "src/App.txt", "one\nW2\nthree\n");
        assertTrue(run(w1, new FakeContext(w1)).success());
        var ctx = new FakeContext(w2);
        resolver(ctx, w2, "src/App.txt", "one\nW1+W2\nthree\n");
        var result = run(w2, ctx);
        assertTrue(result.success(), result.failure());
        assertEquals(List.of(AgentLifecycle.SYNCING, AgentLifecycle.RESOLVING, AgentLifecycle.SYNCING,
            AgentLifecycle.MERGING), ctx.states);
        assertEquals(1, ctx.messages.size());
        var message = ctx.messages.get(0);
        assertTrue(message.contains("src/App.txt"), "lists the conflicting files");
        assertTrue(message.contains("git status") || message.contains("Unmerged"), "has the git status");
        assertTrue(message.contains("jeorje: one"), "lists the commits that came from the source");
        assertTrue(message.contains("sync_done") && message.contains("sync_abort"));
        assertTrue(result.branch().contains("Conflicts resolved during the sync: src/App.txt"));
        assertEquals("one\nW1+W2\nthree\n", Files.readString(root.resolve("src/App.txt")));
    }

    @Test
    void rebaseSyncConflictIsResolvedWithSyncDone() throws Exception {
        setup(config("rebase", "head"));
        var w1 = worktree("a1", "one", SpawnMode.BACKGROUND);
        var w2 = worktree("a2", "two", SpawnMode.BACKGROUND);
        write(w1.path, "src/App.txt", "one\nW1\nthree\n");
        write(w2.path, "src/App.txt", "one\nW2\nthree\n");
        assertTrue(run(w1, new FakeContext(w1)).success());
        var ctx = new FakeContext(w2);
        resolver(ctx, w2, "src/App.txt", "one\nboth\nthree\n");
        var result = run(w2, ctx);
        assertTrue(result.success(), result.failure());
        assertEquals("one\nboth\nthree\n", Files.readString(root.resolve("src/App.txt")));
        assertEquals("", g(root, "log", "--merges", "--format=%s"));
        assertEquals(WorktreeManager.Progress.NONE, manager.inProgress(w2));
    }

    @Test
    void syncDoneRefusesLeftoverConflictMarkers() throws Exception {
        setup(config("merge", "head"));
        var w1 = worktree("a1", "one", SpawnMode.BACKGROUND);
        var w2 = worktree("a2", "two", SpawnMode.BACKGROUND);
        write(w1.path, "src/App.txt", "one\nW1\nthree\n");
        write(w2.path, "src/App.txt", "one\nW2\nthree\n");
        assertTrue(run(w1, new FakeContext(w1)).success());
        var sync = manager.commitRemaining(w2).isPresent() ? manager.sync(w2) : null;
        assertEquals(WorktreeManager.SyncResult.Kind.CONFLICT, sync.kind());
        assertEquals(List.of("src/App.txt"), sync.conflicts());
        var markers = manager.syncDone(w2);
        assertEquals(WorktreeManager.DoneResult.Kind.MARKERS, markers.kind());
        assertTrue(markers.message().contains("App.txt"));
        write(w2.path, "src/App.txt", "fixed\n");
        assertEquals(WorktreeManager.DoneResult.Kind.DONE, manager.syncDone(w2).kind());
        assertEquals(WorktreeManager.Progress.NONE, manager.inProgress(w2));
    }

    @Test
    void syncAbortFailsTheSequenceAndLeavesSourceUnchanged() throws Exception {
        setup(config("merge", "head"));
        var w1 = worktree("a1", "one", SpawnMode.BACKGROUND);
        var w2 = worktree("a2", "two", SpawnMode.BACKGROUND);
        write(w1.path, "src/App.txt", "one\nW1\nthree\n");
        write(w2.path, "src/App.txt", "one\nW2\nthree\n");
        assertTrue(run(w1, new FakeContext(w1)).success());
        var before = g(root, "rev-parse", "HEAD");
        var ctx = new FakeContext(w2);
        ctx.resume = m -> manager.signal(w2.agent, WorktreeManager.Resolution.ABORT, "cannot reconcile the parsers");
        var result = run(w2, ctx);
        assertFalse(result.success());
        assertTrue(result.failure().contains("sync_abort: cannot reconcile the parsers"));
        assertTrue(result.branch().contains(w2.branch));
        assertEquals(before, g(root, "rev-parse", "HEAD"));
        assertEquals(WorktreeManager.Progress.NONE, manager.inProgress(w2), "git merge --abort ran");
        assertTrue(Files.isDirectory(w2.path));
        assertFalse(manager.cleanup(w2, false).removed(), "a failed subagent keeps its worktree");
    }

    @Test
    void unfinishedResolutionFailsWithTheConflictingFiles() throws Exception {
        setup(config("merge", "head"));
        var w1 = worktree("a1", "one", SpawnMode.BACKGROUND);
        var w2 = worktree("a2", "two", SpawnMode.BACKGROUND);
        write(w1.path, "src/App.txt", "one\nW1\nthree\n");
        write(w2.path, "src/App.txt", "one\nW2\nthree\n");
        assertTrue(run(w1, new FakeContext(w1)).success());
        var ctx = new FakeContext(w2);
        var result = run(w2, ctx);
        assertFalse(result.success());
        assertTrue(result.failure().contains("src/App.txt"));
        assertTrue(ctx.messages.size() > 1, "the agent was asked again");
        assertEquals(WorktreeManager.Progress.NONE, manager.inProgress(w2));
    }

    @Test
    void theResolutionRoundStopsAfterTheModelCallBudget() throws Exception {
        setup(config("merge", "head"));
        var w1 = worktree("a1", "one", SpawnMode.BACKGROUND);
        var w2 = worktree("a2", "two", SpawnMode.BACKGROUND);
        write(w1.path, "src/App.txt", "one\nW1\nthree\n");
        write(w2.path, "src/App.txt", "one\nW2\nthree\n");
        assertTrue(run(w1, new FakeContext(w1)).success());
        var ctx = new FakeContext(w2);
        var used = new AtomicInteger();
        ctx.usage = budget -> used.getAndIncrement() == 0 ? 20 : budget;
        var result = run(w2, ctx);
        assertFalse(result.success());
        assertTrue(result.failure().contains("all 30 model calls"), result.failure());
        assertEquals(List.of(30, 10), ctx.budgets, "the second call gets what is left");
        assertEquals(WorktreeManager.Progress.NONE, manager.inProgress(w2));
    }

    // ---- verification ----

    @Test
    void failingVerificationSendsTheAgentBackToWork() throws Exception {
        setup(config("merge", "head", "auto", "delete-if-clean", 3, "make check"));
        var w = worktree("a1", "ver", SpawnMode.WAIT);
        write(w.path, "ver.txt", "broken\n");
        var calls = new AtomicInteger();
        var commands = new ArrayList<String>();
        verifier = (dir, cmd) -> {
            commands.add(cmd);
            return calls.incrementAndGet() == 1 ? new Verifier.Result(false, "BUILD FAILED: ver.txt") : new Verifier.Result(true, "");
        };
        var ctx = new FakeContext(w);
        resolver(ctx, w, "ver.txt", "fixed\n");
        var result = run(w, ctx);
        assertTrue(result.success(), result.failure());
        assertEquals(List.of("make check", "make check"), commands);
        assertTrue(ctx.messages.get(0).contains("BUILD FAILED: ver.txt"));
        assertEquals(List.of(AgentLifecycle.SYNCING, AgentLifecycle.RESOLVING, AgentLifecycle.SYNCING,
            AgentLifecycle.MERGING), ctx.states);
        assertEquals("fixed\n", Files.readString(root.resolve("ver.txt")));
    }

    @Test
    void verificationThatKeepsFailingEndsTheSequence() throws Exception {
        setup(config("merge", "head", "auto", "delete-if-clean", 3, "make check"));
        var w = worktree("a1", "ver", SpawnMode.WAIT);
        write(w.path, "ver.txt", "broken\n");
        verifier = (dir, cmd) -> new Verifier.Result(false, "still red");
        var ctx = new FakeContext(w);
        resolver(ctx, w, "ver.txt", "try\n");
        var before = g(root, "rev-parse", "HEAD");
        var result = run(w, ctx);
        assertFalse(result.success());
        assertTrue(result.failure().contains("verification failed") && result.failure().contains("still red"));
        assertEquals(before, g(root, "rev-parse", "HEAD"));
    }

    @Test
    void storyVerifyCommandWinsOverTheConfiguredOne() throws Exception {
        setup(config("merge", "head", "auto", "delete-if-clean", 3, "config-cmd"));
        var req = new WorktreeManager.CreateRequest(new AgentId("s1"), new AgentId("root"), "B1", "plan", "story", root,
            SpawnMode.BACKGROUND, null, false, "mvn -q test");
        var w = manager.create(req).orElseThrow();
        write(w.path, "b.txt", "b\n");
        var commands = new ArrayList<String>();
        verifier = (dir, cmd) -> {
            commands.add(cmd);
            return new Verifier.Result(true, "");
        };
        assertTrue(run(w, new FakeContext(w)).success());
        assertEquals(List.of("mvn -q test"), commands);
    }

    // ---- race and the integration lock ----

    @Test
    void raceNeedsASecondRound() throws Exception {
        setup(config("merge", "head"));
        var w = worktree("a1", "race", SpawnMode.WAIT);
        write(w.path, "race.txt", "r\n");
        var rounds = new ArrayList<Integer>();
        var ctx = new FakeContext(w);
        var result = steps().beforeMerge(round -> {
            rounds.add(round);
            if (round == 1) {
                try {
                    commit(root, "sibling.txt", "s\n", "sibling merged first");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }).run(ctx);
        assertTrue(result.success(), result.failure());
        assertEquals(List.of(1, 2), rounds);
        assertEquals(List.of(AgentLifecycle.SYNCING, AgentLifecycle.MERGING, AgentLifecycle.SYNCING,
            AgentLifecycle.MERGING), ctx.states);
        assertTrue(Files.exists(root.resolve("sibling.txt")) && Files.exists(root.resolve("race.txt")));
        assertTrue(GIT.run(w.path, "merge-base", "--is-ancestor", "main~1", w.branch).ok());
    }

    @Test
    void afterMaxRoundsTheLockIsHeldFromTheStartOfTheLastSync() throws Exception {
        setup(config("merge", "head", "auto", "delete-if-clean", 2, ""));
        var w = worktree("a1", "busy", SpawnMode.WAIT);
        var other = worktree("a2", "other", SpawnMode.WAIT);
        write(w.path, "busy.txt", "b\n");
        var rounds = new ArrayList<Integer>();
        var lockProbe = new ArrayList<Boolean>();
        var result = steps().beforeMerge(round -> {
            rounds.add(round);
            try {
                commit(root, "s" + round + ".txt", "s\n", "sibling " + round);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).run(new FakeContext(w) {
            @Override
            public void enter(AgentLifecycle phase) {
                super.enter(phase);
                if (phase == AgentLifecycle.SYNCING && states.size() > 4) {
                    // the third (last) round: the lock is held already. A probe on another thread cannot get it.
                    var got = new java.util.concurrent.atomic.AtomicBoolean();
                    var t = Thread.ofVirtual().start(() -> {
                        try (var l = manager.lockIntegration(other, org.github.lscoughlin.jeorje.core.model.CancellationToken.none())) {
                            got.set(true);
                        } catch (InterruptedException ignored) {
                        }
                    });
                    try {
                        t.join(300);
                    } catch (InterruptedException ignored) {
                    }
                    lockProbe.add(got.get());
                    t.interrupt();
                }
            }
        });
        assertTrue(result.success(), result.failure());
        assertEquals(List.of(1, 2), rounds, "no hook in the locked round: nobody can move the branch");
        assertEquals(List.of(false), lockProbe);
        assertTrue(Files.exists(root.resolve("busy.txt")) && Files.exists(root.resolve("s2.txt")));
    }

    // ---- dirty parent ----

    private void dirtyParent() throws IOException {
        write(root, "src/App.txt", "parent edit\n");
    }

    @Test
    void dirtyParentInWaitModeEndsAtOnceWithNotMerged() throws Exception {
        setup(config("merge", "head"));
        var w = worktree("a1", "wait", SpawnMode.WAIT);
        write(w.path, "src/App.txt", "one\nchild\nthree\n");
        dirtyParent();
        var started = System.nanoTime();
        var result = run(w, new FakeContext(w));
        assertTrue(result.success());
        assertTrue(result.branch().startsWith("completed (not merged)"), result.branch());
        assertTrue(result.branch().contains("src/App.txt") && result.branch().contains("/merge wait"));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5, "no waiting");
        assertTrue(parentMessages.isEmpty(), "the parent is blocked, so no inbox message");
        assertFalse(w.merged());
        assertEquals("parent edit\n", Files.readString(root.resolve("src/App.txt")));
        assertFalse(manager.cleanup(w, true).removed(), "not merged is an issue");
        // the parent stashes and runs /merge
        g(root, "stash", "-q");
        var manual = steps().manualMerge(w, null);
        assertTrue(manual.merged(), manual.message());
        assertEquals("one\nchild\nthree\n", Files.readString(root.resolve("src/App.txt")));
        assertTrue(manual.note().startsWith("worktree removed: wait"));
        assertFalse(Files.exists(w.path));
    }

    @Test
    void dirtyParentInBackgroundModeNotifiesAndRetries() throws Exception {
        setup(with(config("merge", "head"), "auto", List.of(), List.of(), 10));
        var w = worktree("a1", "bg", SpawnMode.BACKGROUND);
        write(w.path, "src/App.txt", "one\nchild\nthree\n");
        dirtyParent();
        var steps = new WorktreeCompletion(manager, verifier, (p, m) -> {
            parentMessages.add(m);
            assertEquals(new AgentId("root"), p);
            g(root, "stash", "-q");  // the parent cleans up after the message
        }).retryInterval(Duration.ofMillis(50));
        var result = steps.run(new FakeContext(w));
        assertTrue(result.success(), result.failure());
        assertEquals(1, parentMessages.size());
        assertTrue(parentMessages.get(0).contains("src/App.txt"));
        assertTrue(result.branch().contains("merged into main"), result.branch());
        assertTrue(w.merged());
    }

    @Test
    void dirtyParentInBackgroundModeGivesUpAfterTheWait() throws Exception {
        setup(with(config("merge", "head"), "auto", List.of(), List.of(), 1));
        var w = worktree("a1", "bg", SpawnMode.BACKGROUND);
        write(w.path, "src/App.txt", "one\nchild\nthree\n");
        dirtyParent();
        var result = run(w, new FakeContext(w));
        assertTrue(result.success());
        assertTrue(result.branch().startsWith("completed (not merged)"));
        assertEquals(1, parentMessages.size());
        assertFalse(w.merged());
        assertTrue(Files.isDirectory(w.path) && !branches(root).isEmpty());
        assertEquals("parent edit\n", Files.readString(root.resolve("src/App.txt")));
    }

    // ---- snapshot base ----

    @Test
    void snapshotBaseMergesBackIntoDirtyParentWithoutTouchingIt() throws Exception {
        for (var strategy : List.of("merge", "rebase")) {
            var dir = Files.createDirectories(tmp.resolve("case-" + strategy));
            root = repo(dir);
            manager = manager(root, config(strategy, "snapshot"), new ArrayList<>());
            write(root, "src/App.txt", "parent edit\n");
            write(root, "wip.txt", "untracked\n");
            var w = worktree("a1", "snap", SpawnMode.WAIT);
            write(w.path, "child.txt", "child\n");
            // premise: merging the snapshot branch directly is refused by git
            var direct = GIT.run(root, "merge", "--no-ff", "--no-edit", w.branch);
            assertFalse(direct.ok(), "git refuses a merge of the snapshot branch into a dirty tree");
            assertEquals("parent edit\n", Files.readString(root.resolve("src/App.txt")));
            var ctx = new FakeContext(w);
            var result = run(w, ctx);
            assertTrue(result.success(), result.failure());
            assertTrue(w.merged(), strategy);
            assertTrue(Files.exists(root.resolve("child.txt")));
            assertEquals("parent edit\n", Files.readString(root.resolve("src/App.txt")));
            assertEquals("untracked\n", Files.readString(root.resolve("wip.txt")));
            assertFalse(g(root, "log", "--format=%s").contains("snapshot"), "the snapshot commit is not on the source");
            assertTrue(g(root, "status", "--porcelain").contains("src/App.txt"));
            assertTrue(w.snapshotDropped());
            assertTrue(manager.cleanup(w, true).branchDeleted());
        }
    }

    @Test
    void snapshotSyncUsesRebaseOntoAndResolvesConflicts() throws Exception {
        setup(config("merge", "snapshot"));
        var w = worktree("a1", "snap", SpawnMode.WAIT);
        write(w.path, "src/App.txt", "one\nchild\nthree\n");
        commit(root, "src/App.txt", "one\nparent\nthree\n", "parent edits the same line");
        var ctx = new FakeContext(w);
        resolver(ctx, w, "src/App.txt", "one\nboth\nthree\n");
        var result = run(w, ctx);
        assertTrue(result.success(), result.failure());
        assertEquals("one\nboth\nthree\n", Files.readString(root.resolve("src/App.txt")));
        assertFalse(g(root, "log", "--format=%s").contains("snapshot"));
    }

    // ---- nested subagents ----

    @Test
    void nestedSubagentMergesIntoTheBranchOfItsParent() throws Exception {
        setup(config("merge", "head"));
        var parent = worktree("p1", "parent", SpawnMode.BACKGROUND);
        var child = manager.create(new WorktreeManager.CreateRequest(new AgentId("c1"), new AgentId("p1"), "child",
            "g1", "child task", parent.path, SpawnMode.WAIT, null, false, null)).orElseThrow();
        assertEquals(parent.branch, child.sourceBranch);
        assertEquals(parent.path.toRealPath(), child.sourcePath.toRealPath());
        write(child.path, "child.txt", "c\n");
        var mainBefore = g(root, "rev-parse", "HEAD");
        var result = run(child, new FakeContext(child));
        assertTrue(result.success(), result.failure());
        assertTrue(Files.exists(parent.path.resolve("child.txt")));
        assertFalse(Files.exists(root.resolve("child.txt")), "main is unchanged");
        assertEquals(mainBefore, g(root, "rev-parse", "HEAD"));
        var cleanup = manager.cleanup(child, true);
        assertTrue(cleanup.removed() && cleanup.branchDeleted());
        // the parent then completes and merges the work of the child into main
        write(parent.path, "parent.txt", "p\n");
        assertTrue(run(parent, new FakeContext(parent)).success());
        assertTrue(Files.exists(root.resolve("child.txt")) && Files.exists(root.resolve("parent.txt")));
    }

    // ---- manual merge back ----

    @Test
    void manualMergeBackStopsAfterTheSyncAndMergesOnRequest() throws Exception {
        setup(config("merge", "head", "manual", "delete-if-clean", 3, ""));
        var w = worktree("a1", "man", SpawnMode.BACKGROUND);
        write(w.path, "man.txt", "m\n");
        commit(root, "other.txt", "o\n", "parent moves on");
        var before = g(root, "rev-parse", "HEAD");
        var ctx = new FakeContext(w);
        var result = run(w, ctx);
        assertTrue(result.success());
        assertTrue(result.branch().contains("waits for a manual merge"));
        assertEquals(List.of(AgentLifecycle.SYNCING), ctx.states, "no MERGING state");
        assertEquals(before, g(root, "rev-parse", "HEAD"));
        assertTrue(w.readyForMerge() && !w.merged());
        assertFalse(manager.cleanup(w, true).removed(), "a manual merge that waits is an issue");
        // the source moves again: /merge syncs first
        commit(root, "later.txt", "l\n", "later");
        var manual = steps().manualMerge(w, "squash");
        assertTrue(manual.merged(), manual.message());
        assertTrue(Files.exists(root.resolve("man.txt")) && Files.exists(root.resolve("later.txt")));
        assertEquals("jeorje: man", g(root, "log", "-1", "--format=%s"));
        assertFalse(Files.exists(w.path), "the parent deletes the worktree after a manual merge");
    }

    @Test
    void manualMergeReportsConflictsAndBadInput() throws Exception {
        setup(config("merge", "head"));
        var w1 = worktree("a1", "one", SpawnMode.BACKGROUND);
        var w2 = worktree("a2", "two", SpawnMode.BACKGROUND);
        write(w1.path, "src/App.txt", "one\nW1\nthree\n");
        write(w2.path, "src/App.txt", "one\nW2\nthree\n");
        assertTrue(run(w1, new FakeContext(w1)).success());
        var conflict = steps().manualMerge(w2, null);
        assertFalse(conflict.merged());
        assertTrue(conflict.message().contains("src/App.txt"));
        assertEquals(WorktreeManager.Progress.NONE, manager.inProgress(w2));
        assertFalse(steps().manualMerge(w2, "octopus").merged());
        assertTrue(steps().manualMerge(w1, null).message().contains("already merged"));
    }

    // ---- cleanup, discard, prune, rows ----

    @Test
    void cleanupKeepsWorktreesWithIssues() throws Exception {
        setup(config("merge", "head"));
        var w = worktree("a1", "keepme", SpawnMode.WAIT);
        write(w.path, "x.txt", "x\n");
        assertTrue(run(w, new FakeContext(w)).success());
        write(w.path, "late.txt", "uncommitted after the merge\n");
        var failed = manager.cleanup(w, false);
        assertFalse(failed.removed());
        var dirty = manager.cleanup(w, true);
        assertFalse(dirty.removed());
        assertTrue(dirty.keepReason().contains("uncommitted"));
        assertTrue(Files.isDirectory(w.path));
    }

    @Test
    void onFinishKeepNeverDeletesAndRemoveDeletesOnlyTheDirectory() throws Exception {
        setup(config("merge", "head", "auto", "keep", 3, ""));
        var w = worktree("a1", "k", SpawnMode.WAIT);
        write(w.path, "x.txt", "x\n");
        assertTrue(run(w, new FakeContext(w)).success());
        var kept = manager.cleanup(w, true);
        assertFalse(kept.removed());
        assertTrue(kept.keepReason().contains("keep"));

        var dir2 = Files.createDirectories(tmp.resolve("two"));
        root = repo(dir2);
        manager = manager(root, config("merge", "head", "auto", "remove", 3, ""), new ArrayList<>());
        var r = worktree("a1", "r", SpawnMode.WAIT);
        write(r.path, "unmerged.txt", "u\n");
        g(r.path, "add", "-A");
        g(r.path, "commit", "-q", "-m", "work");
        write(r.path, "dirty.txt", "d\n");
        var removed = manager.cleanup(r, false);
        assertTrue(removed.removed());
        assertFalse(removed.branchDeleted(), "unmerged commits are kept on the branch");
        assertFalse(Files.exists(r.path));
        assertTrue(branches(root).contains(r.branch));
    }

    @Test
    void discardRemovesWorktreeAndBranch() throws Exception {
        setup(config("merge", "head"));
        var w = worktree("a1", "gone", SpawnMode.WAIT);
        write(w.path, "x.txt", "x\n");
        g(w.path, "add", "-A");
        g(w.path, "commit", "-q", "-m", "unmerged work");
        write(w.path, "y.txt", "y\n");
        var stopped = new ArrayList<Path>();
        manager.processStopper(stopped::add);
        var text = manager.discard(w);
        assertTrue(text.contains("deleted the branch"));
        assertFalse(Files.exists(w.path));
        assertEquals("", branches(root));
        assertEquals(List.of(w.path), stopped, "processes in the worktree are stopped first");
        assertTrue(manager.find("gone").isEmpty());
    }

    @Test
    void rowsShowCleanChangedAndMerged() throws Exception {
        setup(config("merge", "head"));
        var clean = worktree("a1", "clean", SpawnMode.WAIT);
        var changed = worktree("a2", "changed", SpawnMode.WAIT);
        var merged = worktree("a3", "merged", SpawnMode.WAIT);
        write(changed.path, "c.txt", "c\n");
        write(merged.path, "m.txt", "m\n");
        assertTrue(run(merged, new FakeContext(merged)).success());
        var states = new java.util.TreeMap<String, String>();
        manager.rows().forEach(r -> states.put(r.name(), r.state()));
        assertEquals("{changed=changed, clean=clean, merged=merged}", states.toString());
        assertEquals(clean, manager.find("a1").orElseThrow());
        assertEquals(clean, manager.find(clean.branch).orElseThrow());
    }

    @Test
    void pruneRemovesMergedWorktreesStillOnDisk() throws Exception {
        setup(config("merge", "head"));
        var done = worktree("a1", "done", SpawnMode.WAIT);
        var open = worktree("a2", "open", SpawnMode.WAIT);
        write(done.path, "d.txt", "d\n");
        assertTrue(run(done, new FakeContext(done)).success());
        write(open.path, "o.txt", "o\n");
        var removed = manager.prune();
        assertEquals(1, removed.size());
        assertFalse(Files.exists(done.path));
        assertTrue(Files.exists(open.path));
        assertFalse(branches(root).contains("done"));
        assertTrue(manager.find("done").isEmpty());
    }

    @Test
    void finisherCleansUpWhenASubagentEnds() throws Exception {
        setup(config("merge", "head"));
        var w = worktree("a1", "fin", SpawnMode.WAIT);
        write(w.path, "x.txt", "x\n");
        assertTrue(run(w, new FakeContext(w)).success());
        var notes = new ArrayList<String>();
        var finisher = new WorktreeFinisher(manager, (parent, text) -> notes.add(parent + ": " + text));
        var completion = new org.github.lscoughlin.jeorje.core.subagent.Completion(w.agent, w.name,
            org.github.lscoughlin.jeorje.core.subagent.Completion.Outcome.COMPLETED, null, "summary", "done");
        finisher.accept(new org.github.lscoughlin.jeorje.core.subagent.SubagentEvent.Finished(w.agent, w.name, completion));
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).startsWith("root: worktree removed: fin"));
        assertFalse(Files.exists(w.path));
        finisher.accept(new org.github.lscoughlin.jeorje.core.subagent.SubagentEvent.Spawned(w.agent, null, "x", 1));
        assertEquals(1, notes.size());
    }

    @Test
    void agentWithoutWorktreeSkipsTheSteps() throws Exception {
        setup(config("merge", "head"));
        var w = worktree("a1", "x", SpawnMode.WAIT);
        manager.discard(w);
        assertEquals(CompletionSteps.Result.skipped(), steps().run(new FakeContext(w)));
    }

    // ---- error cases with a fake git runner ----

    private GitRunner failing(String subcommand, String message) {
        return (d, args, env, timeout) -> {
            if (args.contains(subcommand) && !args.contains("--is-ancestor")) return new GitResult(1, "", message);
            return GIT.run(d, args, env, timeout);
        };
    }

    private Worktree withRunner(GitRunner runner) throws IOException {
        root = repo(tmp);
        var real = manager(root, config("merge", "head"), new ArrayList<>());
        var w = real.create(request(root, "a1", "err", SpawnMode.WAIT)).orElseThrow();
        manager = new WorktreeManager(runner, root, config("merge", "head"), s -> {});
        manager.restore(w);
        return w;
    }

    @Test
    void commitFailureFailsTheSequence() throws Exception {
        var w = withRunner(failing("commit", "fatal: cannot write"));
        write(w.path, "x.txt", "x\n");
        var result = run(w, new FakeContext(w));
        assertFalse(result.success());
        assertTrue(result.failure().contains("Cannot commit"));
    }

    @Test
    void syncErrorWithoutConflictFailsTheSequence() throws Exception {
        var w = withRunner(failing("merge", "fatal: refusing to merge unrelated histories"));
        var result = run(w, new FakeContext(w));
        assertFalse(result.success());
        assertTrue(result.failure().contains("unrelated histories"));
    }

    @Test
    void mergeBackErrorFailsAndKeepsTheBranch() throws Exception {
        root = repo(tmp);
        var real = manager(root, config("merge", "head"), new ArrayList<>());
        var w = real.create(request(root, "a1", "err", SpawnMode.WAIT)).orElseThrow();
        write(w.path, "x.txt", "x\n");
        GitRunner runner = (d, args, env, timeout) -> {
            if (args.contains("merge") && args.contains("--no-ff")) return new GitResult(1, "", "fatal: boom");
            return GIT.run(d, args, env, timeout);
        };
        manager = new WorktreeManager(runner, root, config("merge", "head"), s -> {});
        manager.restore(w);
        var result = run(w, new FakeContext(w));
        assertFalse(result.success());
        assertTrue(result.failure().contains("boom"));
        assertTrue(Files.isDirectory(w.path));
    }

    @Test
    void sourceBranchMustBeCheckedOutForTheMergeBack() throws Exception {
        setup(config("merge", "head"));
        var w = worktree("a1", "off", SpawnMode.WAIT);
        write(w.path, "x.txt", "x\n");
        g(root, "checkout", "-q", "-b", "elsewhere");
        var result = run(w, new FakeContext(w));
        assertFalse(result.success());
        assertTrue(result.failure().contains("not checked out"));
    }

    @Test
    void worktreeRemoveRefusalKeepsTheWorktree() throws Exception {
        root = repo(tmp);
        var real = manager(root, config("merge", "head"), new ArrayList<>());
        var w = real.create(request(root, "a1", "stuck", SpawnMode.WAIT)).orElseThrow();
        write(w.path, "x.txt", "x\n");
        GitRunner runner = (d, args, env, timeout) -> args.contains("remove")
            ? new GitResult(128, "", "fatal: contains modified or untracked files") : GIT.run(d, args, env, timeout);
        manager = new WorktreeManager(runner, root, config("merge", "head"), s -> {});
        manager.restore(w);
        assertTrue(steps().run(new FakeContext(w)).success());
        assertTrue(w.merged());
        var cleanup = manager.cleanup(w, true);
        assertFalse(cleanup.removed());
        assertTrue(cleanup.keepReason().contains("refused"));
    }
}
