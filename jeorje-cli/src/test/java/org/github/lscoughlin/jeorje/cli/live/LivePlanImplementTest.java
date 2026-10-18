package org.github.lscoughlin.jeorje.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.github.lscoughlin.jeorje.cli.CliEnvironment;
import org.github.lscoughlin.jeorje.cli.GlobalOptions;
import org.github.lscoughlin.jeorje.cli.interactive.InteractiveSession;
import org.github.lscoughlin.jeorje.core.implement.StoryState;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.plan.PlanParser;
import org.github.lscoughlin.jeorje.core.worktree.GitRunner;
import org.github.lscoughlin.jeorje.llm.OpenAiCompatibleClient;
import org.github.lscoughlin.jeorje.llm.OpenAiSettings;
import org.github.lscoughlin.jeorje.testkit.StubEmbedder;
import org.jline.terminal.Size;
import org.jline.terminal.impl.ExternalTerminal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Mixin;

/**
 * Runs /plan and /implement in the interactive session against OpenRouter with the default model. It
 * runs only when JEORJE_LIVE=1 and needs OPENROUTER_API_KEY. It never prints the key.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "JEORJE_LIVE", matches = "1")
class LivePlanImplementTest {
    private static final GitRunner GIT = GitRunner.process();
    /** Hard stop for the whole run, so that a wrong loop cannot spend money. */
    private static final int TOTAL_CALL_LIMIT = 60;

    @TempDir Path tmp;

    static final class Holder {
        @Mixin
        GlobalOptions global = new GlobalOptions();
    }

    private static String git(Path dir, String... args) {
        var r = GIT.run(dir, args);
        assertTrue(r.ok(), "git " + String.join(" ", args) + " failed: " + r.text());
        return r.trimmed();
    }

    private static void await(String what, long seconds, BooleanSupplier condition) throws InterruptedException {
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            try {
                if (condition.getAsBoolean()) return;
            } catch (NullPointerException e) {
                // not built yet
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Not reached in " + seconds + " s: " + what);
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void planThenImplementCreatesTheFile() throws Exception {
        var key = System.getenv("OPENROUTER_API_KEY");
        assertTrue(key != null && !key.isBlank(), "OPENROUTER_API_KEY is not set");

        var home = Files.createDirectories(tmp.resolve("home"));
        var repo = Files.createDirectories(tmp.resolve("repo"));
        Files.createDirectories(home.resolve(".jeorje"));
        Files.writeString(home.resolve(".jeorje").resolve("config.yaml"), """
                permissions:
                  rules:
                    - tool: write
                      decision: allow
                    - tool: edit
                      decision: allow
                implement:
                  maxModelCalls: 20
                  maxAttempts: 1
                """);
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.name", "Tester");
        git(repo, "config", "user.email", "tester@example.com");
        git(repo, "config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        Files.writeString(repo.resolve(".gitignore"), ".jeorje/\ntarget/\n");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "initial");

        var calls = new AtomicInteger();
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var env = new CliEnvironment(home, repo, java.util.Map.of("OPENROUTER_API_KEY", key),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                (config, model, apiKey) -> {
                    var real = new OpenAiCompatibleClient(OpenAiSettings.fromConfig(config, model, apiKey));
                    return (org.github.lscoughlin.jeorje.core.port.LlmClient) (LlmRequest request,
                            CancellationToken cancel) -> {
                        if (calls.incrementAndGet() > TOTAL_CALL_LIMIT) {
                            throw new IllegalStateException("Model call limit of the live test reached.");
                        }
                        return real.stream(request, cancel);
                    };
                },
                config -> Optional.of(new StubEmbedder()), false);

        var args = new ArrayList<String>(List.of("--dir", repo.toString(), "--no-mouse"));
        var global = CommandLine.populateCommand(new Holder(), args.toArray(new String[0])).global;

        var keys = new PipedOutputStream();
        var terminalOut = new ByteArrayOutputStream();
        var terminal = new ExternalTerminal("live", "xterm", new PipedInputStream(keys, 1 << 16), terminalOut,
                StandardCharsets.UTF_8);
        terminal.setSize(new Size(120, 40));
        var session = new InteractiveSession(env, global, terminal,
                new InteractiveSession.Hooks(tmp.resolve("history"), Duration.ofMillis(200), null));
        var result = CompletableFuture.supplyAsync(session::run);
        try {
            await("screen up", 60, () -> session.screen() != null && session.agent() != null
                    && terminalOut.size() > 0);
            Thread.sleep(500);

            // Plan mode writes a valid plan.
            type(keys, "/plan add a file named notes.txt with two lines. Write the verification as one plain shell command, "
                    + "with no prefix such as bash:. Use one story. Ask no questions.\r");
            await("plan mode", 30, () -> session.planMode().active());
            answering(keys, session, "plan written", 150,
                    () -> session.planMode().submittedPlan().isPresent());
            var plan = session.planMode().submittedPlan().orElseThrow();
            assertTrue(Files.isRegularFile(plan), "the plan file exists");
            assertTrue(plan.startsWith(repo.resolve("plan")), "the plan is in the plan directory");
            var parsed = PlanParser.parse(plan);
            assertFalse(parsed.stories().isEmpty(), "the plan has stories");
            await("plan turn ends", 120, () -> session.agent().turns().size() >= 1);
            type(keys, "/plan done\r");
            await("plan done", 30, () -> !session.planMode().active());

            // The plan is committed so that the workers start from it.
            git(repo, "add", "-A");
            git(repo, "commit", "-q", "-m", "add the plan");
            var planName = plan.getFileName().toString().replaceFirst("\\.md$", "");

            // /implement runs the stories and merges them back.
            type(keys, "/implement " + planName + "\r");
            answering(keys, session, "implement run", 600, () -> {
                var rows = session.orchestrator().rows();
                return !rows.isEmpty() && !session.orchestrator().active()
                        && rows.stream().allMatch(r -> r.state() == StoryState.DONE
                                || r.state() == StoryState.BLOCKED);
            });
            var rows = session.orchestrator().rows();
            assertTrue(rows.stream().allMatch(r -> r.state() == StoryState.DONE),
                    "all stories are done: " + rows);

            var notes = repo.resolve("notes.txt");
            assertTrue(Files.isRegularFile(notes), "notes.txt exists in the repository after the merge back");
            var lines = Files.readString(notes).lines().filter(l -> !l.isBlank()).toList();
            assertTrue(lines.size() >= 2, "notes.txt has two lines: " + lines);
            assertEquals("", git(repo, "branch", "--list", "jeorje/*"), "worker branches are gone");
            var worktrees = git(repo, "worktree", "list", "--porcelain").lines()
                    .filter(l -> l.startsWith("worktree ")).toList();
            assertEquals(1, worktrees.size(), "the worktrees are removed: " + worktrees);
            assertTrue(calls.get() <= TOTAL_CALL_LIMIT);

            // The key is in no output.
            assertFalse(out.toString(StandardCharsets.UTF_8).contains(key), "key in standard output");
            assertFalse(err.toString(StandardCharsets.UTF_8).contains(key), "key in standard error");
            assertFalse(terminalOut.toString(StandardCharsets.UTF_8).contains(key), "key on the terminal");
        } catch (AssertionError | RuntimeException e) {
            var chat = new StringBuilder();
            try {
                session.screen().chat(new org.github.lscoughlin.jeorje.core.model.AgentId("main")).logicalLines()
                        .forEach(l -> chat.append(l).append('\n'));
            } catch (RuntimeException ignored) {
                // no chat to show
            }
            try {
                chat.append("\nORCHESTRATOR: ").append(session.orchestrator().status()).append('\n');
            } catch (RuntimeException ignored) {
                // no more to show
            }
            System.err.println("LIVE DIAGNOSTICS calls=" + calls.get() + "\n"
                    + chat.toString().replace(key, "***") + "\nstatus: " + session.screen().status().text());
            throw e;
        } finally {
            session.requestExit();
            try {
                result.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                // the test reports its own failure
            }
            terminal.close();
        }
    }

    private static int countAsks(InteractiveSession session) {
        return (int) session.screen().chat(new org.github.lscoughlin.jeorje.core.model.AgentId("main"))
                .logicalLines().stream().filter(l -> l.toString().startsWith("\u25b6 ask_user")).count();
    }

    private static void type(PipedOutputStream keys, String text) throws java.io.IOException {
        keys.write(text.getBytes(StandardCharsets.UTF_8));
        keys.flush();
    }

    /** Answers each new permission prompt with "y" until the condition holds. */
    private static void answering(PipedOutputStream keys, InteractiveSession session, String what, long seconds,
                                  BooleanSupplier condition) throws InterruptedException {
        var last = new Object[1];
        var asked = new int[] {countAsks(session)};
        await(what, seconds, () -> {
            // The model may ask what the lines say. The test answers the question once for each ask_user call.
            var asks = countAsks(session);
            if (asks > asked[0]) {
                asked[0] = asks;
                try {
                    type(keys, "Line one is alpha. Line two is beta. Do not ask more questions.\r");
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            var prompt = session.screen().pendingPrompt();
            if (prompt != null && prompt != last[0]) {
                last[0] = prompt;
                try {
                    type(keys, "y");
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return condition.getAsBoolean();
        });
    }
}
