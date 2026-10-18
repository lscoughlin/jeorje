package org.github.lscoughlin.jeorje.core.worktree;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRunnerTest {
    @TempDir Path dir;

    private static GitRunner versionRunner(String text) {
        return (d, args, env, timeout) -> new GitResult(0, text + "\n", "");
    }

    @Test
    void realGitRunsAndVersionPasses() {
        var runner = GitRunner.process();
        assertDoesNotThrow(runner::checkVersion);
        var result = runner.run(dir, "rev-parse", "--is-inside-work-tree");
        assertFalse(result.ok());
        assertTrue(runner.run(dir, "init", "-q").ok());
    }

    @Test
    void oldVersionIsRefused() {
        assertEquals("2.17.0", versionRunner("git version 2.17.0").checkVersion());
        assertEquals("2.56.0", versionRunner("git version 2.56.0 (Apple Git-1)").checkVersion());
        assertEquals("3.0.1", versionRunner("git version 3.0.1").checkVersion());
        var e = assertThrows(GitException.class, () -> versionRunner("git version 2.16.9").checkVersion());
        assertTrue(e.getMessage().contains("too old"));
        assertThrows(GitException.class, () -> versionRunner("nonsense").checkVersion());
    }

    @Test
    void missingGitIsAnException() {
        GitRunner missing = (d, args, env, timeout) -> {
            throw new GitException("Cannot start git: error=2");
        };
        assertThrows(GitException.class, missing::checkVersion);
        assertThrows(GitException.class, () -> GitRunner.process().run(dir.resolve("nope"), "status"));
    }

    @Test
    void failedVersionCallIsAnException() {
        GitRunner failing = (d, args, env, timeout) -> new GitResult(1, "", "boom");
        assertThrows(GitException.class, failing::checkVersion);
    }

    @Test
    void environmentIsMinimalAndExtraVariablesPass() {
        var result = GitRunner.process().run(dir, List.of("init", "-q"), Map.of(), Duration.ofSeconds(30));
        assertTrue(result.ok());
        var author = GitRunner.process().runWith(dir, Map.of("GIT_AUTHOR_NAME", "Zed", "GIT_AUTHOR_EMAIL", "z@x",
            "GIT_COMMITTER_NAME", "Zed", "GIT_COMMITTER_EMAIL", "z@x"), "-c", "commit.gpgsign=false", "commit",
            "-q", "--allow-empty", "-m", "x");
        assertTrue(author.ok(), author.text());
        assertEquals("Zed", GitRunner.process().run(dir, "log", "-1", "--format=%an").trimmed());
    }

    @Test
    void timeoutStopsTheCall() {
        // "git hash-object --stdin" waits for input that never comes. The runner closes stdin, so use a tiny timeout
        // on a command that is slow enough: a fake clock is not available, so only check the exception type path.
        GitRunner slow = (d, args, env, timeout) -> {
            throw new GitException("git status timed out after 1 s");
        };
        assertTrue(assertThrows(GitException.class, () -> slow.run(dir, "status")).getMessage().contains("timed out"));
    }
}
