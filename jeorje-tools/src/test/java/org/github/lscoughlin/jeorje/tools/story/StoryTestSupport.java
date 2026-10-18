package org.github.lscoughlin.jeorje.tools.story;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.implement.ImplementEvent;
import org.github.lscoughlin.jeorje.core.implement.ImplementOrchestrator;
import org.github.lscoughlin.jeorje.core.implement.Reviewer;
import org.github.lscoughlin.jeorje.core.implement.RunStateStore;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.subagent.SubagentEvent;
import org.github.lscoughlin.jeorje.core.subagent.SubagentLimits;
import org.github.lscoughlin.jeorje.core.worktree.GitRunner;
import org.github.lscoughlin.jeorje.core.worktree.WorktreeManager;
import org.github.lscoughlin.jeorje.testkit.FakeTool;
import org.github.lscoughlin.jeorje.testkit.InMemoryContextStore;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.github.lscoughlin.jeorje.tools.registry.Required;

/** Test doubles and a rig for the tests of {@code /implement}. They use the real {@code git}. */
final class StoryTestSupport {
    static final GitRunner GIT = GitRunner.process();
    static final String PLAN_TITLE = "Secret Plan Title";
    static final String PLAN_GOAL = "Secret plan goal text";

    private StoryTestSupport() {}

    // ---- git ----

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
        write(dir, ".gitignore", "target/\n");
        g(dir, "add", "-A");
        g(dir, "commit", "-q", "-m", "initial");
        return dir;
    }

    static void write(Path dir, String file, String text) throws IOException {
        var p = dir.resolve(file);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    // ---- plan text ----

    /** One story of a test plan. */
    record Spec(String id, String title, List<String> deps, List<String> touches, String verification,
                String model) {
        static Spec of(String id, String title, String file) {
            return new Spec(id, title, List.of(), List.of(file), "`test -f " + file + "`", null);
        }

        Spec after(String... ids) { return new Spec(id, title, List.of(ids), touches, verification, model); }

        Spec touching(String... paths) { return new Spec(id, title, deps, List.of(paths), verification, model); }

        Spec verifying(String command) {
            return new Spec(id, title, deps, touches, command == null ? null : "`" + command + "`", model);
        }
    }

    static String planText(String status, String verification, Spec... stories) {
        var sb = new StringBuilder("---\ntitle: " + PLAN_TITLE + "\nstatus: " + status + "\ngoal: " + PLAN_GOAL
            + "\ncreated: 2026-10-18\nupdated: 2026-10-18\nstories: " + stories.length + "\n");
        if (verification != null) sb.append("verification: ").append(verification).append('\n');
        sb.append("---\n\n# Plan: ").append(PLAN_TITLE).append('\n');
        for (var s : stories) {
            sb.append("\n## Story ").append(s.id()).append(": ").append(s.title()).append('\n');
            sb.append("- **Goal:** Goal of ").append(s.id()).append(".\n");
            sb.append("- **Depends on:** ").append(s.deps().isEmpty() ? "none" : String.join(", ", s.deps())).append('\n');
            sb.append("- **Touches:** ").append(String.join(", ", s.touches().stream().map(t -> "`" + t + "`").toList()))
                .append('\n');
            if (s.model() != null) sb.append("- **Model:** ").append(s.model()).append('\n');
            sb.append("\n### Context\nContext of ").append(s.id()).append(".\n");
            sb.append("\n### Acceptance criteria\n- The file of ").append(s.id()).append(" exists.\n");
            if (s.verification() != null) sb.append("\n### Verification\n").append(s.verification()).append('\n');
            sb.append("\n### Tasks\n- [ ] ").append(s.id()).append(".T1 Create the file of ").append(s.id()).append(".\n");
        }
        return sb.toString();
    }

    // ---- tools ----

    /** Writes a file in the working directory of the worker. */
    static final class PutFile extends RecordTool<PutFile.Input> {
        record Input(@Required @Describe("path") String path, @Required @Describe("content") String content) {}

        PutFile() { super("put_file", "Write a file.", Input.class, false); }

        @Override
        protected ToolResultData run(Input input, ToolContext context) throws Exception {
            write(context.workingDirectory(), input.path(), input.content());
            return ToolResultData.ok("Wrote " + input.path() + " in " + context.workingDirectory());
        }
    }

    /** Runs a shell command in the working directory of the worker. */
    static final class Shell extends RecordTool<Shell.Input> {
        record Input(@Required @Describe("command") String command) {}

        Shell() { super("shell", "Run a command.", Input.class, false); }

        @Override
        protected ToolResultData run(Input input, ToolContext context) throws Exception {
            var p = new ProcessBuilder("sh", "-c", input.command()).directory(context.workingDirectory().toFile())
                .redirectErrorStream(true).start();
            var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(60, TimeUnit.SECONDS);
            return ToolResultData.ok("exit " + p.exitValue() + "\n" + out);
        }
    }

    static List<Tool> baseTools() {
        return List.of(new PutFile(), new Shell(), FakeTool.returning("spawn_agent", "no"),
            FakeTool.returning("plan_submit", "no"), FakeTool.returning("ask_user", "no"));
    }

    // ---- the scripted model ----

    @FunctionalInterface
    interface Responder {
        Response next(LlmRequest request, int index, CancellationToken cancel) throws Exception;
    }

    /**
     * A model that answers by the story of the caller. The story id is in the first user message of a worker. The
     * summary writer and the reviewer have no tools, so they are told apart by their first message.
     */
    static final class Brain implements LlmClient {
        private static final Pattern STORY = Pattern.compile("## Story (\\S+?):");
        private static final Pattern AGENT = Pattern.compile("^Agent name: (\\S+)");
        final Map<String, Responder> workers = new ConcurrentHashMap<>();
        final Map<String, List<LlmRequest>> requests = new ConcurrentHashMap<>();
        final List<LlmRequest> summaryRequests = new CopyOnWriteArrayList<>();
        final Map<String, String> summaries = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

        Brain worker(String story, Responder responder) {
            workers.put(story, responder);
            return this;
        }

        /** Plays the responses in order. After the last one the worker ends its turn with a text. */
        Brain script(String story, Response... steps) {
            return worker(story, (r, i, c) -> i < steps.length ? steps[i] : Response.text("Nothing more to do."));
        }

        Brain summary(String story, String text) {
            summaries.put(story, text);
            return this;
        }

        List<LlmRequest> of(String story) { return requests.getOrDefault(story, List.of()); }

        @Override
        public Flow.Publisher<LlmEvent> stream(LlmRequest request, CancellationToken cancel) {
            Response response;
            try {
                response = respond(request, cancel);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return new ScriptedLlmClient(response).stream(request, cancel);
        }

        private Response respond(LlmRequest request, CancellationToken cancel) throws Exception {
            var first = text(request.messages().get(0));
            if (request.tools().isEmpty()) {
                summaryRequests.add(request);
                var m = AGENT.matcher(first);
                var story = m.find() ? m.group(1) : "?";
                return Response.text(summaries.getOrDefault(story, summaryText("work", "x.txt")));
            }
            var m = STORY.matcher(first);
            if (!m.find()) throw new IllegalStateException("No story in the first message: " + first);
            var story = m.group(1);
            requests.computeIfAbsent(story, k -> new CopyOnWriteArrayList<>()).add(request);
            var index = counters.computeIfAbsent(story, k -> new AtomicInteger()).getAndIncrement();
            var responder = workers.get(story);
            if (responder == null) throw new IllegalStateException("No script for " + story);
            return responder.next(request, index, cancel);
        }
    }

    static String text(org.github.lscoughlin.jeorje.core.model.Message message) {
        var sb = new StringBuilder();
        for (var c : message.content()) if (c instanceof Text t) sb.append(t.text());
        return sb.toString();
    }

    /** The outputs of all tool results in a request. */
    static List<String> toolResults(LlmRequest request) {
        var out = new ArrayList<String>();
        for (var m : request.messages()) {
            for (var c : m.content()) if (c instanceof ToolResult r) out.add(r.output());
        }
        return out;
    }

    static String summaryText(String done, String files) {
        return """
            Outcome: completed
            Done: %s
            Files: %s
            Branch: merged
            Decisions: none
            Verification: ran the check, it passed.
            Open issues: none
            Keywords: Story, Goal, Tasks""".formatted(done, files);
    }

    // ---- standard worker steps ----

    static Response call(String id, String tool, String json) { return Response.toolCall(id, tool, json); }

    /** A worker that writes one file, finishes its task, and ends its story. */
    static Response[] simple(String story, String file, String content) {
        var task = story + ".T1";
        return new Response[] {
            call(story + "-c1", "put_file", "{\"path\":\"" + file + "\",\"content\":\"" + content.replace("\n", "\\n") + "\"}"),
            call(story + "-c2", "task_done", "{\"taskId\":\"" + task + "\",\"note\":\"created\"}"),
            call(story + "-c3", "story_done", "{\"summary\":\"DRAFT-" + story + " wrote " + file
                + "\",\"filesChanged\":[\"" + file + "\"]}"),
            Response.text("Finished " + story + ".")
        };
    }

    // ---- the rig ----

    /** An orchestrator with a real git repository and a scripted model. */
    static final class Rig implements AutoCloseable {
        final Path root;
        final Path plan;
        final Path stateFile;
        final Brain brain;
        final InMemoryContextStore store = new InMemoryContextStore();
        final List<ImplementEvent> events = new CopyOnWriteArrayList<>();
        final List<SubagentEvent> subEvents = new CopyOnWriteArrayList<>();
        final List<String> notices = new CopyOnWriteArrayList<>();
        final WorktreeManager manager;
        ImplementOrchestrator orchestrator;

        final int parallel;
        final Reviewer reviewer;
        final int maxAttempts;

        Rig(Path tmp, boolean git, String planText, int parallel, Brain brain, Reviewer reviewer, int maxAttempts,
            RunStateStore stateStore) throws IOException {
            this.brain = brain;
            this.parallel = parallel;
            this.reviewer = reviewer;
            this.maxAttempts = maxAttempts;
            this.root = git ? repo(tmp) : Files.createDirectories(tmp.resolve("repo"));
            this.plan = root.resolve("plan").resolve("demo.md");
            write(root, "plan/demo.md", planText);
            if (git) {
                g(root, "add", "-A");
                g(root, "commit", "-q", "-m", "add the plan");
            }
            this.stateFile = tmp.resolve("state").resolve("implement.json");
            var cfg = new JeorjeConfig.Worktree("auto", "head", "jeorje", List.of(), List.of(), "delete-if-clean",
                "merge", "auto", 300, new JeorjeConfig.Sync(3, 30, ""));
            this.manager = git ? new WorktreeManager(GIT, root, cfg, notices::add) : null;
            this.orchestrator = create(brain, stateStore == null ? RunStateStore.file(stateFile) : stateStore);
        }

        /** A new orchestrator on the same repository, as after a restart of Jeorje. */
        ImplementOrchestrator create(Brain model, RunStateStore stateStore) {
            var settings = AgentSettings.defaults("s1", root, 100_000).withSleeper(d -> { });
            var implement = new JeorjeConfig.Implement(parallel, "", reviewer == null ? "none" : "advisor",
                maxAttempts, 200, true);
            return ImplementOrchestrator.builder("s1", settings, m -> model, implement)
                .store(store).worktrees(manager).reviewer(reviewer).stateStore(stateStore)
                .events(events::add).subagentEvents(subEvents::add)
                .limits(SubagentLimits.defaults().withAskTimeout(Duration.ofSeconds(5)))
                .mergeRetryInterval(Duration.ofMillis(50))
                .baseTools(baseTools()).workerTools(StoryTools.factory(List.of())).build();
        }

        String planFile() throws IOException { return Files.readString(plan, StandardCharsets.UTF_8); }

        List<ImplementEvent.StoryChanged> changes(String story) {
            return events.stream().filter(e -> e instanceof ImplementEvent.StoryChanged c && c.storyId().equals(story))
                .map(e -> (ImplementEvent.StoryChanged) e).toList();
        }

        /** The largest number of stories that ran at the same time. */
        int maxConcurrent() {
            var running = new java.util.HashSet<String>();
            var max = 0;
            for (var e : events) {
                if (e instanceof ImplementEvent.StoryStarted s) {
                    running.add(s.storyId());
                    max = Math.max(max, running.size());
                } else if (e instanceof ImplementEvent.StoryChanged c
                    && c.state() != org.github.lscoughlin.jeorje.core.implement.StoryState.RUNNING) {
                    running.remove(c.storyId());
                }
            }
            return max;
        }

        @Override
        public void close() { orchestrator.close(); }
    }

    static void waitUntil(BooleanSupplier condition) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in 30 s");
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
