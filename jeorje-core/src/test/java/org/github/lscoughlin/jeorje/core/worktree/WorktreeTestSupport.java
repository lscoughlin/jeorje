package org.github.lscoughlin.jeorje.core.worktree;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.subagent.AgentLifecycle;
import org.github.lscoughlin.jeorje.core.subagent.CompletionSteps;
import org.github.lscoughlin.jeorje.core.subagent.SpawnMode;

/** Helpers for the worktree tests. They run the real {@code git}. */
final class WorktreeTestSupport {
    private WorktreeTestSupport() {}

    static final GitRunner GIT = GitRunner.process();

    /** Runs git and asserts success. Returns the trimmed output. */
    static String g(Path dir, String... args) {
        var r = GIT.run(dir, args);
        assertTrue(r.ok(), "git " + String.join(" ", args) + " failed: " + r.text());
        return r.trimmed();
    }

    static Path repo(Path parent) throws IOException {
        var dir = Files.createDirectories(parent.resolve("repo"));
        g(dir, "init", "-q", "-b", "main");
        g(dir, "config", "user.name", "Tester");
        g(dir, "config", "user.email", "tester@example.com");
        g(dir, "config", "commit.gpgsign", "false");
        write(dir, "README.md", "hello\n");
        write(dir, "src/App.txt", "one\ntwo\nthree\n");
        write(dir, ".gitignore", "target/\n.env\n");
        g(dir, "add", "-A");
        g(dir, "commit", "-q", "-m", "initial");
        return dir;
    }

    static void write(Path dir, String file, String text) throws IOException {
        var p = dir.resolve(file);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    static void commit(Path dir, String file, String text, String message) throws IOException {
        write(dir, file, text);
        g(dir, "add", "-A");
        g(dir, "commit", "-q", "-m", message);
    }

    static JeorjeConfig.Worktree config(String merge, String base) {
        return config(merge, base, "auto", "delete-if-clean", 3, "");
    }

    static JeorjeConfig.Worktree config(String merge, String base, String mergeBack, String onFinish, int maxRounds,
                                        String verify) {
        return new JeorjeConfig.Worktree("auto", base, "jeorje", List.of(), List.of(), onFinish, merge, mergeBack, 300,
            new JeorjeConfig.Sync(maxRounds, 30, verify));
    }

    static JeorjeConfig.Worktree with(JeorjeConfig.Worktree c, String isolation, List<String> copy, List<String> link,
                                      int waitSeconds) {
        return new JeorjeConfig.Worktree(isolation, c.base(), c.branchPrefix(), copy, link, c.onFinish(), c.merge(),
            c.mergeBack(), waitSeconds, c.sync());
    }

    static WorktreeManager manager(Path root, JeorjeConfig.Worktree cfg, List<String> notices) {
        return new WorktreeManager(GIT, root, cfg, notices::add);
    }

    static WorktreeManager.CreateRequest request(Path root, String agent, String name, SpawnMode mode) {
        return new WorktreeManager.CreateRequest(new AgentId(agent), new AgentId("root"), name, "g1", "Task for "
            + name + "\nsecond line", root, mode, null, false, null);
    }

    /** A completion context that a test drives by hand. */
    static class FakeContext implements CompletionSteps.Context {
        final AgentId agent;
        final String name;
        final Path dir;
        final CancellationToken token = CancellationToken.none();
        final List<AgentLifecycle> states = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        Consumer<String> resume = m -> {};
        final List<Integer> budgets = new ArrayList<>();
        /** The model calls that a resume with a budget reports. By default none. */
        java.util.function.IntUnaryOperator usage = budget -> 0;

        FakeContext(Worktree w) {
            this.agent = w.agent;
            this.name = w.name;
            this.dir = w.path;
        }

        @Override public AgentId agent() { return agent; }
        @Override public String name() { return name; }
        @Override public Path workingDirectory() { return dir; }
        @Override public String isolation() { return "worktree"; }
        @Override public CancellationToken cancel() { return token; }
        @Override public void enter(AgentLifecycle phase) { states.add(phase); }
        @Override public AutoCloseable internalWait(AgentId holder, String reason) { return () -> {}; }

        @Override
        public void resumeWork(String message) {
            messages.add(message);
            resume.accept(message);
        }

        @Override
        public int resumeWork(String message, int maxModelCalls) {
            budgets.add(maxModelCalls);
            resumeWork(message);
            return usage.applyAsInt(maxModelCalls);
        }
    }
}
