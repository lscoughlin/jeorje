package org.github.lscoughlin.jeorje.cli.interactive;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.cli.CliEnvironment;
import org.github.lscoughlin.jeorje.cli.GlobalOptions;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.core.worktree.GitRunner;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.github.lscoughlin.jeorje.testkit.StubEmbedder;
import org.jline.terminal.Size;
import org.jline.terminal.impl.ExternalTerminal;
import picocli.CommandLine;
import picocli.CommandLine.Mixin;

/** A rig for the session tests: a git repository, a fake terminal, and a scripted model that routes by caller. */
final class SessionRig implements AutoCloseable {
    static final GitRunner GIT = GitRunner.process();

    /** Holds the global options so that picocli can fill them. */
    static final class Holder {
        @Mixin
        GlobalOptions global = new GlobalOptions();
    }

    /** The routing model: one script for the main agent, one for each story, and a summary writer. */
    static final class Router implements LlmClient {
        private static final Pattern STORY = Pattern.compile("## Story (\\S+?):");
        private static final Pattern AGENT = Pattern.compile("^Agent name: (\\S+)");
        final ScriptedLlmClient main;
        final Map<String, ScriptedLlmClient> workers = new ConcurrentHashMap<>();
        final List<LlmRequest> mainRequests = new CopyOnWriteArrayList<>();

        Router(ScriptedLlmClient main) {
            this.main = main;
        }

        Router story(String id, Response... steps) {
            workers.put(id, new ScriptedLlmClient(steps));
            return this;
        }

        @Override
        public Flow.Publisher<LlmEvent> stream(LlmRequest request, CancellationToken cancel) {
            var first = request.messages().isEmpty() ? "" : text(request.messages().get(0));
            if (request.tools().isEmpty()) {
                var m = AGENT.matcher(first);
                var story = m.find() ? m.group(1) : "?";
                return new ScriptedLlmClient(Response.text(summary(story))).stream(request, cancel);
            }
            var m = STORY.matcher(first);
            if (m.find()) {
                var worker = workers.get(m.group(1));
                if (worker == null) throw new IllegalStateException("No script for story " + m.group(1));
                return worker.stream(request, cancel);
            }
            mainRequests.add(request);
            return main.stream(request, cancel);
        }
    }

    static String text(Message message) {
        var sb = new StringBuilder();
        for (var c : message.content()) if (c instanceof Text t) sb.append(t.text());
        return sb.toString();
    }

    static String summary(String story) {
        return """
            Outcome: completed
            Done: work of %s
            Files: %s.txt
            Branch: merged
            Decisions: none
            Verification: ran the check, it passed.
            Open issues: none
            Keywords: Story, Goal, Tasks""".formatted(story, story.toLowerCase());
    }

    final Path tmp;
    final Path home;
    final Path repo;
    final ExternalTerminal terminal;
    final PipedOutputStream keys = new PipedOutputStream();
    final ByteArrayOutputStream terminalOut = new ByteArrayOutputStream();
    final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    final Router router;
    /** Gives a client for a model slug. When null, every model uses the router. */
    java.util.function.Function<String, LlmClient> byModel;
    InteractiveSession session;
    CompletableFuture<Integer> result;

    SessionRig(Path tmp, Router router, String userConfig) throws IOException {
        this.tmp = tmp;
        this.router = router;
        this.home = Files.createDirectories(tmp.resolve("home"));
        this.repo = Files.createDirectories(tmp.resolve("repo"));
        if (userConfig != null) {
            Files.createDirectories(home.resolve(".jeorje"));
            Files.writeString(home.resolve(".jeorje").resolve("config.yaml"), userConfig);
        }
        var in = new PipedInputStream(keys, 1 << 16);
        terminal = new ExternalTerminal("test", "xterm", in, terminalOut, StandardCharsets.UTF_8);
        terminal.setSize(new Size(100, 30));
    }

    static String git(Path dir, String... args) {
        var r = GIT.run(dir, args);
        assertTrue(r.ok(), "git " + String.join(" ", args) + " failed: " + r.text());
        return r.trimmed();
    }

    /** Makes the repository with one commit. {@code .jeorje/} and {@code target/} are ignored. */
    void initRepo() throws IOException {
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.name", "Tester");
        git(repo, "config", "user.email", "tester@example.com");
        git(repo, "config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        Files.writeString(repo.resolve(".gitignore"), ".jeorje/\ntarget/\n");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "initial");
    }

    CliEnvironment env() {
        return new CliEnvironment(home, repo, Map.of(), new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8), (config, model, key) -> byModel == null ? router : byModel.apply(model),
                config -> Optional.of(new StubEmbedder()), false);
    }

    GlobalOptions options(String... extra) {
        var args = new java.util.ArrayList<String>(List.of("--dir", repo.toString()));
        args.addAll(List.of(extra));
        return CommandLine.populateCommand(new Holder(), args.toArray(new String[0])).global;
    }

    /** Starts the session on its own thread. */
    void start(String... options) {
        session = new InteractiveSession(env(), options(options), terminal,
                new InteractiveSession.Hooks(tmp.resolve("history"), java.time.Duration.ofMillis(50), null));
        result = CompletableFuture.supplyAsync(session::run);
    }

    /** Starts the session on its own thread and resumes an earlier session. An empty id means the latest session. */
    void startResumed(String resume, String... options) {
        session = new InteractiveSession(env(), options(options), terminal,
                new InteractiveSession.Hooks(tmp.resolve("history"), java.time.Duration.ofMillis(50), null), resume);
        result = CompletableFuture.supplyAsync(session::run);
    }

    void type(String text) throws IOException {
        keys.write(text.getBytes(StandardCharsets.UTF_8));
        keys.flush();
    }

    String terminalText() {
        terminal.flush();
        return terminalOut.toString(StandardCharsets.UTF_8);
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < end) {
            try {
                if (condition.getAsBoolean()) return;
            } catch (NullPointerException e) {
                // the session is not built yet
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Not reached in 30 s: " + what);
    }

    @Override
    public void close() throws IOException {
        if (session != null) session.requestExit();
        if (result != null) {
            try {
                result.get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                // the test reports its own failure
            }
        }
        terminal.close();
    }
}
