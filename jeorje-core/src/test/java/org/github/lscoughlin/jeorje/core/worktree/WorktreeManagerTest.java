package org.github.lscoughlin.jeorje.core.worktree;

import static org.github.lscoughlin.jeorje.core.worktree.WorktreeTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.subagent.SpawnMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreeManagerTest {
    @TempDir Path tmp;

    @Test
    void headBaseCreatesWorktreeOnBranchWithoutParentChanges() throws IOException {
        var root = repo(tmp);
        write(root, "src/App.txt", "dirty\n");
        write(root, "new.txt", "untracked\n");
        var m = manager(root, config("merge", "head"), new ArrayList<>());
        var w = m.create(request(root, "a1", "Fix Parser", SpawnMode.WAIT)).orElseThrow();
        assertEquals(root.resolve(".jeorje/worktrees/g1/fix-parser").toRealPath(), w.path.toRealPath());
        assertEquals("jeorje/g1/fix-parser", w.branch);
        assertEquals("main", w.sourceBranch);
        assertEquals("one\ntwo\nthree\n", Files.readString(w.path.resolve("src/App.txt")));
        assertFalse(Files.exists(w.path.resolve("new.txt")));
        assertEquals(g(root, "rev-parse", "HEAD"), w.baseCommit);
        assertEquals("jeorje/g1/fix-parser", g(w.path, "symbolic-ref", "--short", "HEAD"));
        // the main tree status shows no worktree directory
        assertFalse(g(root, "status", "--porcelain").contains(".jeorje"));
        assertEquals("dirty\n", Files.readString(root.resolve("src/App.txt")));
    }

    @Test
    void branchAndPathGetNumericSuffixOnClash() throws IOException {
        var root = repo(tmp);
        var m = manager(root, config("merge", "head"), new ArrayList<>());
        var a = m.create(request(root, "a1", "worker", SpawnMode.WAIT)).orElseThrow();
        var b = m.create(request(root, "a2", "worker", SpawnMode.WAIT)).orElseThrow();
        g(root, "branch", "jeorje/g1/worker-3");
        var c = m.create(request(root, "a3", "worker", SpawnMode.WAIT)).orElseThrow();
        assertEquals("jeorje/g1/worker", a.branch);
        assertEquals("jeorje/g1/worker-2", b.branch);
        assertEquals("jeorje/g1/worker-4", c.branch);
        assertTrue(b.path.endsWith("worker-2"));
        assertTrue(Files.isDirectory(c.path));
    }

    @Test
    void snapshotBaseHasDirtyStateAndLeavesParentUntouched() throws IOException {
        var root = repo(tmp);
        write(root, "src/App.txt", "dirty\n");
        write(root, "new.txt", "untracked\n");
        write(root, "target/out.bin", "ignored\n");
        var headBefore = g(root, "rev-parse", "HEAD");
        var m = manager(root, config("merge", "snapshot"), new ArrayList<>());
        var w = m.create(request(root, "a1", "snap", SpawnMode.WAIT)).orElseThrow();
        assertTrue(w.snapshot);
        assertEquals("dirty\n", Files.readString(w.path.resolve("src/App.txt")));
        assertEquals("untracked\n", Files.readString(w.path.resolve("new.txt")));
        assertFalse(Files.exists(w.path.resolve("target/out.bin")));
        assertEquals(headBefore, g(root, "rev-parse", "HEAD"));
        assertEquals(headBefore, g(w.path, "rev-parse", "HEAD~1"));
        assertEquals("", g(root, "diff", "--cached", "--name-only"), "the index of the parent is unchanged");
        assertEquals("main", g(root, "symbolic-ref", "--short", "HEAD"));
        assertTrue(g(root, "status", "--porcelain").contains("new.txt"));
    }

    @Test
    void copyAndLinkIgnoredFiles() throws IOException {
        var root = repo(tmp);
        write(root, ".env", "KEY=1\n");
        write(root, "target/cache/x.bin", "x\n");
        write(root, "secret/a.txt", "a\n");
        var cfg = with(config("merge", "head"), "auto", List.of(".env", "../outside", "missing.txt"),
            List.of("target/cache"), 300);
        var m = manager(root, cfg, new ArrayList<>());
        var w = m.create(request(root, "a1", "copy", SpawnMode.WAIT)).orElseThrow();
        assertEquals("KEY=1\n", Files.readString(w.path.resolve(".env")));
        assertFalse(Files.isSymbolicLink(w.path.resolve(".env")));
        assertTrue(Files.isSymbolicLink(w.path.resolve("target/cache")));
        assertEquals("x\n", Files.readString(w.path.resolve("target/cache/x.bin")));
        assertFalse(Files.exists(tmp.resolve("outside")));
        // a worktree with linked ignored files is clean and can be removed without force
        assertEquals("clean", m.state(w));
        var cleanup = m.cleanup(w, true);
        assertFalse(cleanup.removed(), "not merged, so kept");
    }

    @Test
    void isolationModes() throws IOException {
        var root = repo(tmp);
        var notices = new ArrayList<String>();
        // never
        var never = manager(root, with(config("merge", "head"), "never", List.of(), List.of(), 300), notices);
        assertTrue(never.create(request(root, "a1", "x", SpawnMode.WAIT)).isEmpty());
        // per-call worktree overrides never
        var forced = new WorktreeManager.CreateRequest(new AgentId("a2"), null, "y", "g1", "t", root, SpawnMode.WAIT,
            "worktree", false, null);
        assertTrue(never.create(forced).isPresent());
        // per-call shared overrides auto
        var auto = manager(root, config("merge", "head"), notices);
        var shared = new WorktreeManager.CreateRequest(new AgentId("a3"), null, "z", "g1", "t", root, SpawnMode.WAIT,
            "shared", false, null);
        assertTrue(auto.create(shared).isEmpty());
        // explicit workdir gets no worktree
        var explicit = new WorktreeManager.CreateRequest(new AgentId("a4"), null, "w", "g1", "t", root, SpawnMode.WAIT,
            null, true, null);
        assertTrue(auto.create(explicit).isEmpty());
        assertTrue(notices.isEmpty());
    }

    @Test
    void autoFallsBackWithOneNoticeAndAlwaysFails() throws IOException {
        var plain = Files.createDirectories(tmp.resolve("plain"));
        var notices = new ArrayList<String>();
        var auto = manager(plain, config("merge", "head"), notices);
        assertTrue(auto.create(request(plain, "a1", "x", SpawnMode.WAIT)).isEmpty());
        assertTrue(auto.create(request(plain, "a2", "y", SpawnMode.WAIT)).isEmpty());
        assertEquals(1, notices.size(), "one notice only");
        var always = manager(plain, with(config("merge", "head"), "always", List.of(), List.of(), 300), notices);
        assertThrows(WorktreeException.class, () -> always.create(request(plain, "a3", "z", SpawnMode.WAIT)));
    }

    @Test
    void missingGitFallsBackOrFails() throws IOException {
        var root = repo(tmp);
        GitRunner missing = (d, args, env, timeout) -> {
            throw new GitException("Cannot start git: error=2");
        };
        var notices = new ArrayList<String>();
        var auto = new WorktreeManager(missing, root, config("merge", "head"), notices::add);
        assertTrue(auto.create(request(root, "a1", "x", SpawnMode.WAIT)).isEmpty());
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).contains("share the working directory"));
        var always = new WorktreeManager(missing, root, with(config("merge", "head"), "always", List.of(), List.of(), 300),
            notices::add);
        var e = assertThrows(WorktreeException.class, () -> always.create(request(root, "a2", "y", SpawnMode.WAIT)));
        assertTrue(e.getMessage().contains("git"));
    }

    @Test
    void failedWorktreeAddFallsBackInAutoMode() throws IOException {
        var root = repo(tmp);
        GitRunner failing = (d, args, env, timeout) -> {
            if (args.contains("worktree") && args.contains("add")) return new GitResult(128, "", "fatal: disk full");
            return GIT.run(d, args, env, timeout);
        };
        var notices = new ArrayList<String>();
        var auto = new WorktreeManager(failing, root, config("merge", "head"), notices::add);
        assertTrue(auto.create(request(root, "a1", "x", SpawnMode.WAIT)).isEmpty());
        assertTrue(notices.get(0).contains("disk full"));
    }

    @Test
    void oldGitFallsBack() throws IOException {
        var root = repo(tmp);
        GitRunner old = (d, args, env, timeout) -> args.contains("--version")
            ? new GitResult(0, "git version 2.10.0\n", "") : GIT.run(d, args, env, timeout);
        var notices = new ArrayList<String>();
        var auto = new WorktreeManager(old, root, config("merge", "head"), notices::add);
        assertTrue(auto.create(request(root, "a1", "x", SpawnMode.WAIT)).isEmpty());
        assertTrue(notices.get(0).contains("too old"));
    }

    @Test
    void detachedHeadHasNoSourceBranch() throws IOException {
        var root = repo(tmp);
        g(root, "checkout", "-q", "--detach");
        var always = manager(root, with(config("merge", "head"), "always", List.of(), List.of(), 300), new ArrayList<>());
        assertThrows(WorktreeException.class, () -> always.create(request(root, "a1", "x", SpawnMode.WAIT)));
    }

    @Test
    void startupPrunesAndReportsLeftovers() throws IOException {
        var root = repo(tmp);
        var m = manager(root, config("merge", "head"), new ArrayList<>());
        var a = m.create(request(root, "a1", "keep", SpawnMode.WAIT)).orElseThrow();
        var b = m.create(request(root, "a2", "gone", SpawnMode.WAIT)).orElseThrow();
        // b's directory vanishes: git still has a record until prune
        deleteTree(b.path);
        var fresh = manager(root, config("merge", "head"), new ArrayList<>());
        var report = fresh.startup(List.of(b.path));
        assertEquals(List.of(b.path.toAbsolutePath().normalize()), report.missing());
        assertEquals(1, report.leftover().size());
        assertEquals(a.path.toRealPath(), report.leftover().get(0).toRealPath());
        assertEquals(1, fresh.listOnDisk().size());
        assertFalse(g(root, "worktree", "list").contains("gone"), "git forgot the vanished worktree");
    }

    @Test
    void commitRemainingUsesFallbackIdentity() throws IOException {
        var root = repo(tmp);
        var m = manager(root, config("merge", "head"), new ArrayList<>());
        var w = m.create(request(root, "a1", "ident", SpawnMode.WAIT)).orElseThrow();
        g(root, "config", "--unset", "user.name");
        g(root, "config", "--unset", "user.email");
        // isolate from the global git config of the machine
        var runner = new GitRunner() {
            @Override
            public GitResult run(Path d, List<String> args, java.util.Map<String, String> env, java.time.Duration t) {
                var all = new java.util.LinkedHashMap<>(env);
                all.put("GIT_CONFIG_GLOBAL", "/dev/null");
                all.put("GIT_CONFIG_NOSYSTEM", "1");
                return GIT.run(d, args, all, t);
            }
        };
        var isolated = new WorktreeManager(runner, root, config("merge", "head"), s -> {});
        isolated.restore(w);
        write(w.path, "x.txt", "x\n");
        assertTrue(isolated.commitRemaining(w).isPresent());
        assertEquals("Jeorje <jeorje@localhost>", g(w.path, "log", "-1", "--format=%an <%ae>"));
        assertEquals("jeorje: ident", g(w.path, "log", "-1", "--format=%s"));
        assertTrue(g(w.path, "log", "-1", "--format=%b").contains("Task for ident"));
        assertTrue(isolated.commitRemaining(w).isEmpty(), "nothing left to commit");
    }

    @Test
    void usesGitIdentityOfTheUser() throws IOException {
        var root = repo(tmp);
        var m = manager(root, config("merge", "head"), new ArrayList<>());
        var w = m.create(request(root, "a1", "ident", SpawnMode.WAIT)).orElseThrow();
        write(w.path, "x.txt", "x\n");
        m.commitRemaining(w);
        assertEquals("Tester", g(w.path, "log", "-1", "--format=%an"));
    }

    @Test
    void integrationLockSerializesAndHonorsCancel() throws Exception {
        var root = repo(tmp);
        var m = manager(root, config("merge", "head"), new ArrayList<>());
        var a = m.create(request(root, "a1", "one", SpawnMode.WAIT)).orElseThrow();
        var b = m.create(request(root, "a2", "two", SpawnMode.WAIT)).orElseThrow();
        var token = org.github.lscoughlin.jeorje.core.model.CancellationToken.none();
        var held = m.lockIntegration(a, token);
        var got = new java.util.concurrent.atomic.AtomicBoolean();
        var thread = Thread.ofVirtual().start(() -> {
            try (var l = m.lockIntegration(b, token)) {
                got.set(true);
            } catch (InterruptedException e) {
                // cancelled
            }
        });
        Thread.sleep(250);
        assertFalse(got.get(), "same source branch: the second waits");
        held.close();
        thread.join(5000);
        assertTrue(got.get());
        var held2 = m.lockIntegration(a, token);
        var cancelled = org.github.lscoughlin.jeorje.core.model.CancellationToken.none();
        cancelled.cancel();
        assertThrows(InterruptedException.class, () -> m.lockIntegration(b, cancelled));
        held2.close();
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (var p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(p);
        }
    }
}
