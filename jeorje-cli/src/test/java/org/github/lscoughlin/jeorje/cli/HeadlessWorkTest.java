package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.plan.PlanParser;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.core.worktree.GitRunner;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.github.lscoughlin.jeorje.testkit.StubEmbedder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** {@code jeorje run "/plan ..."}, {@code "/implement ..."}, and {@code --plan} with no interface. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class HeadlessWorkTest {
    private static final GitRunner GIT = GitRunner.process();
    private static final String CONFIG = """
            permissions:
              rules:
                - tool: write
                  decision: allow
            implement:
              maxAttempts: 1
            """;

    private static final String PLAN_JSON = """
            {"title":"One File","goal":"Make one file.","name":"one-file","stories":[
             {"id":"S1","title":"First file","goal":"Create s1.txt.","touches":["s1.txt"],
              "context":"Create the file s1.txt in the root of the repository.",
              "acceptanceCriteria":["The file s1.txt exists."],"verification":"`test -f s1.txt`",
              "tasks":[{"id":"S1.T1","text":"Create s1.txt."}]}]}
            """.replace("\n", "");

    /** Routes a request to the script of the main agent or to the script of a story. */
    private static final class Router implements LlmClient {
        private static final Pattern STORY = Pattern.compile("## Story (\\S+?):");
        private static final Pattern AGENT = Pattern.compile("^Agent name: (\\S+)");
        final ScriptedLlmClient main;
        final Map<String, ScriptedLlmClient> workers = new ConcurrentHashMap<>();

        Router(ScriptedLlmClient main) { this.main = main; }

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
                return new ScriptedLlmClient(Response.text("""
                        Outcome: completed
                        Done: work of %s
                        Files: %s.txt
                        Branch: merged
                        Decisions: none
                        Verification: ran the check, it passed.
                        Open issues: none
                        Keywords: Story, Goal, Tasks""".formatted(story, story.toLowerCase()))).stream(request, cancel);
            }
            var m = STORY.matcher(first);
            if (m.find()) {
                var worker = workers.get(m.group(1));
                if (worker == null) throw new IllegalStateException("No script for story " + m.group(1));
                return worker.stream(request, cancel);
            }
            return main.stream(request, cancel);
        }
    }

    private static String text(Message message) {
        var sb = new StringBuilder();
        for (var c : message.content()) if (c instanceof Text t) sb.append(t.text());
        return sb.toString();
    }

    private static Response[] worker(String story, String file) {
        return new Response[] {
            Response.toolCall(story + "-c1", "write", "{\"path\":\"" + file + "\",\"content\":\"" + story + "\"}"),
            Response.toolCall(story + "-c2", "task_done", "{\"taskId\":\"" + story + ".T1\",\"note\":\"created\"}"),
            Response.toolCall(story + "-c3", "story_done", "{\"summary\":\"DRAFT " + story
                    + "\",\"filesChanged\":[\"" + file + "\"]}"),
            Response.text("Finished " + story + ".")
        };
    }

    private static Response[] blockedWorker() {
        return new Response[] {
            Response.toolCall("b1", "story_blocked", "{\"reason\":\"cannot do it\"}"), Response.text("Blocked."),
            Response.toolCall("b2", "story_blocked", "{\"reason\":\"cannot do it\"}"), Response.text("Blocked.")};
    }

    @TempDir Path tmp;
    private Path home;
    private Path repo;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private static String git(Path dir, String... args) {
        var r = GIT.run(dir, args);
        assertTrue(r.ok(), "git " + String.join(" ", args) + " failed: " + r.text());
        return r.trimmed();
    }

    @BeforeEach
    void setUp() throws Exception {
        home = Files.createDirectories(tmp.resolve("home"));
        repo = Files.createDirectories(tmp.resolve("repo"));
        Files.createDirectories(home.resolve(".jeorje"));
        Files.writeString(home.resolve(".jeorje").resolve("config.yaml"), CONFIG);
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.name", "Tester");
        git(repo, "config", "user.email", "tester@example.com");
        git(repo, "config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        Files.writeString(repo.resolve(".gitignore"), ".jeorje/\ntarget/\n");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "initial");
    }

    private int run(LlmClient llm, String... args) {
        var env = new CliEnvironment(home, repo, Map.of(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), (config, model, key) -> llm,
                config -> Optional.of(new StubEmbedder()), false);
        return JeorjeMain.execute(env, args);
    }

    private String stdout() { return out.toString(StandardCharsets.UTF_8); }

    private String stderr() { return err.toString(StandardCharsets.UTF_8); }

    private static String planText(String status, int stories) {
        var sb = new StringBuilder("---\ntitle: One\nstatus: " + status + "\ngoal: Make files.\ncreated: 2026-10-18\n"
                + "updated: 2026-10-18\nstories: " + stories + "\n---\n\n# Plan: One\n");
        for (var i = 1; i <= stories; i++) {
            sb.append("\n## Story S").append(i).append(": File ").append(i).append("\n- **Goal:** Goal of S").append(i)
                    .append(".\n- **Depends on:** none\n- **Touches:** `s").append(i).append(".txt`\n\n### Context\n"
                    + "Context.\n\n### Acceptance criteria\n- The file exists.\n\n### Verification\n`test -f s")
                    .append(i).append(".txt`\n\n### Tasks\n- [ ] S").append(i).append(".T1 Create the file.\n");
        }
        return sb.toString();
    }

    private void commitPlan(String status, int stories) throws Exception {
        Files.createDirectories(repo.resolve("plan"));
        Files.writeString(repo.resolve("plan/one.md"), planText(status, stories), StandardCharsets.UTF_8);
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "plan");
    }

    // ---- /plan ----

    @Test
    void planWritesTheFileAndPrintsItsPath() throws Exception {
        var main = new ScriptedLlmClient(Response.toolCall("p1", "plan_submit", PLAN_JSON),
                Response.text("The plan is ready."));
        var code = run(new Router(main), "run", "/plan Build one file");
        assertEquals(0, code, stderr());
        var file = repo.resolve("plan/one-file.md");
        assertTrue(Files.isRegularFile(file));
        assertTrue(stdout().contains("Plan: " + file), stdout());
        var plan = PlanParser.parse(file);
        assertEquals("draft", plan.status());
        assertEquals(1, plan.stories().size());
        // The first request carries the plan-mode section and the goal.
        var first = main.requests().getFirst();
        assertTrue(first.toString().contains("You are in plan mode"));
        assertTrue(first.toString().contains("Build one file"));
        assertEquals(0, new org.github.lscoughlin.jeorje.core.session.SessionStore(repo).list().stream()
                .filter(s -> !"idle".equals(s.manifest().runState())).count());
    }

    @Test
    void planWithoutAGoalFails() {
        var code = run(new Router(new ScriptedLlmClient()), "run", "/plan");
        assertEquals(1, code);
        assertTrue(stderr().contains("needs a goal"), stderr());
    }

    @Test
    void planStopsOnAQuestionWithoutYesAndExitsWithTwo() {
        var main = new ScriptedLlmClient(
                Response.toolCall("q1", "ask_user", "{\"question\":\"Which format?\",\"choices\":[\"JSON\",\"YAML\"]}"),
                Response.text("unreachable"));
        var code = run(new Router(main), "run", "/plan Build a config reader");
        assertEquals(HeadlessWork.EXIT_QUESTION, code, stderr());
        assertTrue(stdout().contains("Which format?"), stdout());
        assertTrue(stdout().contains("JSON") && stdout().contains("YAML"), stdout());
        assertFalse(Files.exists(repo.resolve("plan/one-file.md")));
    }

    @Test
    void planAnswersWithTheFirstChoiceWithYes() {
        var main = new ScriptedLlmClient(
                Response.toolCall("q1", "ask_user", "{\"question\":\"Which format?\",\"choices\":[\"JSON\",\"YAML\"]}"),
                Response.toolCall("p1", "plan_submit", PLAN_JSON),
                Response.text("Done."));
        var code = run(new Router(main), "run", "--yes", "/plan Build a config reader");
        assertEquals(0, code, stderr());
        assertTrue(Files.isRegularFile(repo.resolve("plan/one-file.md")));
        assertTrue(main.requests().get(1).toString().contains("User answer: JSON"), "the answer is the first choice");
    }

    @Test
    void planThatWritesNoFileExitsWithOne() {
        var main = new ScriptedLlmClient(Response.text("I need more details."));
        var code = run(new Router(main), "run", "/plan Something vague");
        assertEquals(1, code);
        assertTrue(stdout().contains("I need more details."), stdout());
        assertTrue(stderr().contains("No plan was written"), stderr());
    }

    // ---- /implement ----

    @Test
    void implementRunsTheStoriesAndExitsWithZero() throws Exception {
        commitPlan("approved", 2);
        var router = new Router(new ScriptedLlmClient()).story("S1", worker("S1", "s1.txt"))
                .story("S2", worker("S2", "s2.txt"));
        var code = run(router, "run", "/implement one");
        assertEquals(0, code, stderr());
        assertEquals("S1", Files.readString(repo.resolve("s1.txt")));
        assertEquals("S2", Files.readString(repo.resolve("s2.txt")));
        assertTrue(stdout().contains("Plan "), stdout());
        assertTrue(stdout().contains("S1 File 1: done"), stdout());
        assertEquals("done", PlanParser.parse(repo.resolve("plan/one.md")).status());
        assertEquals("", git(repo, "branch", "--list", "jeorje/*"), "worker branches are gone");
    }

    @Test
    void implementSelectsStoriesAndFindsThePlanWithoutAName() throws Exception {
        commitPlan("approved", 2);
        var router = new Router(new ScriptedLlmClient()).story("S2", worker("S2", "s2.txt"));
        var code = run(router, "run", "/implement S2");
        assertEquals(0, code, stderr() + stdout());
        assertTrue(Files.exists(repo.resolve("s2.txt")));
        assertFalse(Files.exists(repo.resolve("s1.txt")));
    }

    @Test
    void planOptionRunsThePlanFileFromAPath() throws Exception {
        commitPlan("approved", 1);
        var router = new Router(new ScriptedLlmClient()).story("S1", worker("S1", "s1.txt"));
        var code = run(router, "run", "--plan", "plan/one.md");
        assertEquals(0, code, stderr());
        assertTrue(Files.exists(repo.resolve("s1.txt")));
    }

    @Test
    void blockedStoryExitsWithOne() throws Exception {
        commitPlan("approved", 1);
        var router = new Router(new ScriptedLlmClient()).story("S1", blockedWorker());
        var code = run(router, "run", "/implement one");
        assertEquals(1, code, stderr());
        assertTrue(stdout().contains("blocked"), stdout());
        assertTrue(stdout().contains("cannot do it"), stdout());
        assertEquals("blocked", PlanParser.parse(repo.resolve("plan/one.md")).status());
    }

    @Test
    void draftPlanNeedsYesOrYolo() throws Exception {
        commitPlan("draft", 1);
        var router = new Router(new ScriptedLlmClient()).story("S1", worker("S1", "s1.txt"));
        var code = run(router, "run", "/implement one");
        assertEquals(1, code);
        assertTrue(stderr().contains("Use --yes or --yolo"), stderr());
        assertEquals("draft", PlanParser.parse(repo.resolve("plan/one.md")).status());
        assertFalse(Files.exists(repo.resolve("s1.txt")));

        err.reset();
        out.reset();
        code = run(router, "run", "--yes", "/implement one");
        assertEquals(0, code, stderr());
        assertTrue(Files.exists(repo.resolve("s1.txt")));
        assertEquals("done", PlanParser.parse(repo.resolve("plan/one.md")).status());
    }

    @Test
    void missingPlanExitsWithOne() {
        var code = run(new Router(new ScriptedLlmClient()), "run", "/implement nothing");
        assertEquals(1, code);
        assertTrue(stderr().contains("No plan named nothing"), stderr());
    }

    // ---- status, pause, resume, stop ----

    @Test
    void statusPrintsTheStateOfTheLastRun() throws Exception {
        assertEquals(0, run(new Router(new ScriptedLlmClient()), "run", "/implement status"));
        assertTrue(stdout().contains("No run."), stdout());

        commitPlan("approved", 1);
        out.reset();
        var router = new Router(new ScriptedLlmClient()).story("S1", blockedWorker());
        assertEquals(1, run(router, "run", "/implement one"));

        out.reset();
        assertEquals(0, run(router, "run", "/implement status"));
        var text = stdout();
        assertTrue(text.contains("blocked"), text);
        assertTrue(text.contains("S1"), text);
        assertTrue(text.contains("plan/one.md"), text);
    }

    @Test
    void pauseResumeAndStopNeedAnActiveRun() {
        for (var word : new String[] {"pause", "resume", "stop"}) {
            err.reset();
            assertEquals(1, run(new Router(new ScriptedLlmClient()), "run", "/implement " + word));
            assertTrue(stderr().contains("No run is active"), stderr());
        }
    }

    @Test
    void runWithoutTaskOrPlanFails() {
        assertEquals(1, run(new Router(new ScriptedLlmClient()), "run"));
        assertTrue(stderr().contains("--plan"), stderr());
    }
}
