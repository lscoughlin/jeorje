package org.github.lscoughlin.jeorje.cli.interactive;

import static org.github.lscoughlin.jeorje.cli.interactive.SessionRig.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.cli.ProjectRuleWriter;
import org.github.lscoughlin.jeorje.cli.Wiring;
import org.github.lscoughlin.jeorje.core.agent.Agent;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.agent.ContextBuilder;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.config.ApiKeyResolver;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SessionToolsTest {
    @TempDir Path tmp;

    private static final AgentId MAIN = new AgentId("main");

    private static List<String> names(LlmRequest request) {
        return request.tools().stream().map(ToolDefinition::name).toList();
    }

    private static String toolResults(LlmRequest request) {
        var sb = new StringBuilder();
        request.messages().forEach(m -> m.content().forEach(c -> {
            if (c instanceof ToolResult r) sb.append(r.output()).append('\n');
        }));
        return sb.toString();
    }

    private static void ready(SessionRig rig) throws Exception {
        await("screen up", () -> rig.session.screen() != null && rig.session.slash() != null
                && rig.session.agent() != null && rig.terminalText().contains("ready"));
    }

    private static void say(SessionRig rig, String text, int turns) throws Exception {
        rig.type(text + "\r");
        await("turn " + turns, () -> rig.session.agent().turns().size() == turns);
    }

    private static String chat(SessionRig rig) {
        return rig.session.screen().chat(MAIN).plainText();
    }

    @Test
    void theAdvisorIsOnByDefaultAndTheSwitchTurnsItOffAndOn() throws Exception {
        var main = new ScriptedLlmClient(Response.text("one"), Response.text("two"), Response.text("three"));
        var router = new SessionRig.Router(main);
        try (var rig = new SessionRig(tmp, router, "logging:\n  level: INFO\n")) {
            rig.initRepo();
            rig.start();
            ready(rig);
            say(rig, "first", 1);
            var first = router.mainRequests.getFirst();
            assertTrue(names(first).contains("advisor"), "on by default: " + names(first));
            assertTrue(first.system().contains("- advisor:"));
            assertTrue(rig.session.slash().execute("/advisor none").output().contains("now off"));
            say(rig, "second", 2);
            assertFalse(names(router.mainRequests.get(1)).contains("advisor"));
            assertTrue(rig.session.slash().execute("/advisor").output().contains("~deepseek/deepseek-pro-latest"));
            say(rig, "third", 3);
            assertTrue(names(router.mainRequests.getLast()).contains("advisor"));
        }
    }

    @Test
    void aConfigValueOfNoneTurnsTheAdvisorOffAtTheStart() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient(Response.text("one")));
        try (var rig = new SessionRig(tmp, router, "models:\n  advisor: none\n")) {
            rig.initRepo();
            rig.start();
            ready(rig);
            say(rig, "first", 1);
            assertFalse(names(router.mainRequests.getFirst()).contains("advisor"));
        }
    }

    @Test
    void theToolListFollowsTheAdvisorModel() throws Exception {
        var main = new ScriptedLlmClient(
                Response.text("one"),
                Response.text("two"),
                Response.toolCall("a1", "advisor", "{\"question\":\"Is it fine?\"}"),
                Response.text("three"),
                Response.text("four"));
        var advisorModel = new ScriptedLlmClient(Response.text("Looks good, add tests."));
        var router = new SessionRig.Router(main);
        try (var rig = new SessionRig(tmp, router, "models:\n  advisor: none\n")) {
            rig.byModel = model -> "vendor/strong".equals(model) ? advisorModel : router;
            rig.initRepo();
            rig.start();
            ready(rig);

            say(rig, "first", 1);
            var first = router.mainRequests.getFirst();
            assertFalse(names(first).contains("advisor"), names(first).toString());
            assertFalse(first.system().contains("- advisor:"), "the system prompt does not list the tool");

            assertTrue(rig.session.slash().execute("/advisor vendor/strong").output().contains("vendor/strong"));
            say(rig, "second", 2);
            var second = router.mainRequests.get(1);
            assertTrue(names(second).contains("advisor"), names(second).toString());
            assertTrue(second.system().contains("- advisor:"));

            say(rig, "third", 3);
            var after = router.mainRequests.getLast();
            assertTrue(toolResults(after).contains("Looks good, add tests."), toolResults(after));
            assertEquals(1, advisorModel.callCount());
            var advisorRequest = advisorModel.requests().getFirst();
            assertTrue(advisorRequest.tools().isEmpty(), "the advisor has no tools");
            var last = SessionRig.text(advisorRequest.messages().getLast());
            assertTrue(last.contains("[Advisor request] Is it fine?"), last);
            var all = new StringBuilder();
            advisorRequest.messages().forEach(m -> all.append(SessionRig.text(m)));
            assertTrue(all.toString().contains("first"), "the advisor sees the working context");

            assertTrue(rig.session.slash().execute("/advisor none").output().length() > 0);
            say(rig, "fourth", 4);
            var off = router.mainRequests.getLast();
            assertFalse(names(off).contains("advisor"), names(off).toString());
            assertFalse(off.system().contains("- advisor:"));
        }
    }

    @Test
    void anAdvisorModelInTheConfigRegistersTheToolAtStart() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient(Response.text("one")));
        try (var rig = new SessionRig(tmp, router, "models:\n  advisor: vendor/strong\n")) {
            rig.initRepo();
            rig.start();
            ready(rig);
            say(rig, "first", 1);
            assertTrue(names(router.mainRequests.getFirst()).contains("advisor"));
        }
    }

    private static final String FORM = "{\"title\":\"Setup\",\"questions\":["
            + "{\"id\":\"db\",\"prompt\":\"Which database?\",\"options\":[\"sqlite\",\"postgres\"]},"
            + "{\"id\":\"checks\",\"prompt\":\"Which checks?\",\"kind\":\"multi\",\"options\":[\"unit\",\"lint\",\"e2e\"]}]}";

    private static void formShown(SessionRig rig) throws Exception {
        await("the form", () -> rig.session.screen().pendingForm() != null);
    }

    @Test
    void askUserShowsTheFormAndReturnsTheAnswerToTheModel() throws Exception {
        var main = new ScriptedLlmClient(
                Response.toolCall("q1", "ask_user", "{\"question\":\"Which color?\",\"choices\":[\"red\",\"blue\"]}"),
                Response.text("Blue it is."));
        var router = new SessionRig.Router(main);
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            ready(rig);
            rig.type("pick one\r");
            formShown(rig);
            await("the question", () -> chat(rig).contains("Question from main: Which color?"));
            var shown = String.join("\n", rig.session.screen().renderRegion(24, 80).stream().map(Object::toString).toList());
            assertTrue(shown.contains("red") && shown.contains("blue"), shown);
            rig.type("2");
            Thread.sleep(100);
            rig.type("\r");
            Thread.sleep(100);
            rig.type("\r");
            await("the turn ends", () -> rig.session.agent().turns().size() == 1);
            assertTrue(toolResults(router.mainRequests.getLast()).contains("User answer: blue"),
                    toolResults(router.mainRequests.getLast()));
            assertNull(rig.session.screen().pendingForm());
        }
    }

    @Test
    void aFormWithTwoQuestionsGivesStructuredAnswers() throws Exception {
        var main = new ScriptedLlmClient(Response.toolCall("q1", "ask_user", FORM), Response.text("Noted."));
        var router = new SessionRig.Router(main);
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            ready(rig);
            rig.type("set up\r");
            formShown(rig);
            for (var key : new String[] {"2", "\r", "1", "3", "\t", "\r"}) {
                rig.type(key);
                Thread.sleep(100);
            }
            await("the turn ends", () -> rig.session.agent().turns().size() == 1);
            var results = toolResults(router.mainRequests.getLast());
            assertTrue(results.contains("- db \"Which database?\": postgres"), results);
            assertTrue(results.contains("- checks \"Which checks?\": unit; e2e"), results);
        }
    }

    @Test
    void chatAboutThisReturnsToTheAgentAndTheUserCanTypeAMessage() throws Exception {
        var main = new ScriptedLlmClient(
                Response.toolCall("q1", "ask_user", FORM),
                Response.text("Sqlite has no server. Postgres has a server."),
                Response.text("Thanks."));
        var router = new SessionRig.Router(main);
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            ready(rig);
            rig.type("set up\r");
            formShown(rig);
            rig.type("c");
            Thread.sleep(100);
            rig.type("which?");
            Thread.sleep(100);
            rig.type("\r");
            await("the first turn ends", () -> rig.session.agent().turns().size() == 1);
            var results = toolResults(router.mainRequests.getLast());
            assertTrue(results.contains("The user wants to talk about this before answering."), results);
            assertTrue(results.contains("Note from the user: which?"), results);
            assertTrue(chat(rig).contains("form: chat requested"), chat(rig));
            say(rig, "ok, use sqlite", 2);
            assertNull(rig.session.screen().pendingForm());
        }
    }

    @Test
    void ctrlCWhileAFormWaitsEndsTheTurnAndRemovesTheForm() throws Exception {
        var main = new ScriptedLlmClient(Response.toolCall("q1", "ask_user", FORM), Response.text("never"));
        var router = new SessionRig.Router(main);
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            ready(rig);
            rig.type("set up\r");
            formShown(rig);
            Thread.sleep(200);
            rig.type("\u0003");
            await("the form is gone", () -> rig.session.screen().pendingForm() == null);
            await("the turn ends", () -> rig.session.agent().turns().size() == 1);
        }
    }

    @Test
    void aCommandToolFromTheConfigRuns() throws Exception {
        var config = """
                tools:
                  extensions:
                    - name: shout
                      description: Gives back its input.
                      command: [cat]
                      readOnly: true
                      schema:
                        type: object
                        properties:
                          text:
                            type: string
                permissions:
                  rules:
                    - tool: shout
                      decision: allow
                """;
        var main = new ScriptedLlmClient(
                Response.toolCall("s1", "shout", "{\"text\":\"zebrafish\"}"),
                Response.text("Done."));
        var router = new SessionRig.Router(main);
        try (var rig = new SessionRig(tmp, router, config)) {
            rig.initRepo();
            rig.start();
            ready(rig);
            say(rig, "call it", 1);
            var requests = router.mainRequests;
            assertTrue(names(requests.getFirst()).contains("shout"), names(requests.getFirst()).toString());
            assertTrue(toolResults(requests.getLast()).contains("zebrafish"), toolResults(requests.getLast()));
        }
    }

    @Test
    void clearStartsAFreshConversationAndKeepsTheHistory() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient(Response.text("one"), Response.text("two"),
                Response.text("three")));
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            ready(rig);
            say(rig, "first topic", 1);
            say(rig, "second topic", 2);
            var pinned = rig.session.slash().execute("/pin");
            assertTrue(pinned.output().contains("Pinned"), pinned.output());
            assertFalse(rig.session.agent().pinnedTexts().isEmpty());
            var before = router.mainRequests.size();
            var transcript = rig.session.session().transcriptPath();
            // A turn joins the finished turns before the transcript write, so the test waits for the write.
            await("second turn on disk", () -> {
                try {
                    // The message rows are written while the turn runs, so the end row of turn 2 marks the write.
                    return Files.readAllLines(transcript).stream().filter(l -> l.contains("\"turn_end\"")).count() >= 2;
                } catch (java.io.IOException e) {
                    return false;
                }
            });
            var lines = Files.readAllLines(transcript).size();

            var result = rig.session.slash().execute("/clear");
            assertFalse(result.error(), result.output());
            assertTrue(rig.session.agent().turns().isEmpty(), "turns " + rig.session.agent().turns().size()
                    + " " + rig.session.agent().pinnedTexts());
            assertTrue(rig.session.agent().pinnedTexts().isEmpty());
            var view = chat(rig);
            assertTrue(view.contains("New conversation."), view);
            assertFalse(view.contains("first topic"), view);
            assertEquals(lines, Files.readAllLines(transcript).size(), "the transcript stays");

            say(rig, "new topic", 1);
            var request = router.mainRequests.get(before);
            var texts = new StringBuilder();
            request.messages().forEach(m -> texts.append(SessionRig.text(m)).append('\n'));
            assertTrue(texts.toString().contains("new topic"), texts.toString());
            assertFalse(texts.toString().contains("first topic"), texts.toString());
            assertFalse(texts.toString().contains("Pinned"), texts.toString());
            assertTrue(rig.session.agent().turns().getFirst().number() > 2, "the turn numbers continue");
        }
    }

    @Test
    void clearTakesNoArguments() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient(Response.text("one")));
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            ready(rig);
            say(rig, "a topic", 1);
            assertTrue(rig.session.slash().execute("/clear now").error());
            assertEquals(1, rig.session.agent().turns().size());
            assertFalse(chat(rig).contains("New conversation."), chat(rig));
        }
    }

    @Test
    void compactPrunesALargeContextAndShowsTheEvent() throws Exception {
        var lines = new StringBuilder();
        for (var i = 0; i < 300; i++) lines.append("line ").append(i).append(" of the big file with some words\n");
        var responses = new java.util.ArrayList<Response>();
        for (var turn = 1; turn <= 6; turn++) {
            responses.add(Response.toolCall("r" + turn, "read", "{\"path\":\"big.txt\"}"));
            responses.add(Response.text("Read it " + turn + "."));
        }
        var router = new SessionRig.Router(new ScriptedLlmClient(responses));
        try (var rig = new SessionRig(tmp, router, "context:\n  recentTurns: 1\n")) {
            rig.initRepo();
            Files.writeString(rig.repo.resolve("big.txt"), lines.toString());
            rig.start();
            ready(rig);
            assertTrue(rig.session.slash().execute("/compact").output().contains("nothing to compact"));
            for (var turn = 1; turn <= 4; turn++) say(rig, "look " + turn, turn);
            var before = router.mainRequests.size();
            assertFalse(chat(rig).contains("[compacted:"), "the policy did not run yet");

            var output = rig.session.slash().execute("/compact").output();
            assertTrue(output.contains("Compacted"), output);
            await("the compacted event", () -> chat(rig).contains("[compacted:"));

            // The compaction is already applied. The very next request is smaller than the last one before it.
            say(rig, "look 5", 5);
            var requests = router.mainRequests;
            var sizeBefore = ContextBuilder.rawEstimate(requests.get(before - 1));
            var sizeAfter = ContextBuilder.rawEstimate(requests.get(before));
            assertTrue(sizeAfter < sizeBefore, "after " + sizeAfter + " before " + sizeBefore);
        }
    }

    @Test
    void compactInASubagentViewCompactsTheForegroundSubagent() throws Exception {
        var lines = new StringBuilder();
        for (var n = 0; n < 300; n++) lines.append("line ").append(n).append(" of the big output with some words\n");
        var big = lines.toString();
        var steps = new java.util.ArrayList<Response>();
        for (var turn = 1; turn <= 5; turn++) {
            steps.add(Response.toolCall("b" + turn, "big", "{}"));
            steps.add(Response.text("Done " + turn));
        }
        var subLlm = new ScriptedLlmClient(steps);
        var subEvents = new java.util.concurrent.CopyOnWriteArrayList<AgentEvent>();
        var tool = new org.github.lscoughlin.jeorje.core.port.Tool() {
            public org.github.lscoughlin.jeorje.core.model.ToolDefinition definition() {
                return new org.github.lscoughlin.jeorje.core.model.ToolDefinition("big", "Big output",
                        JsonMapper.builder().build().createObjectNode().put("type", "object"));
            }

            public boolean readOnly() { return true; }

            public org.github.lscoughlin.jeorje.core.model.ToolResultData execute(
                    tools.jackson.databind.JsonNode input, org.github.lscoughlin.jeorje.core.port.ToolContext context) {
                return org.github.lscoughlin.jeorje.core.model.ToolResultData.ok(big);
            }
        };
        var router = new SessionRig.Router(new ScriptedLlmClient(Response.text("main idle")));
        try (var rig = new SessionRig(tmp, router, "context:\n  recentTurns: 1\n")) {
            rig.initRepo();
            rig.start();
            ready(rig);
            var subId = new AgentId("sub-compact-1");
            var settings = AgentSettings.defaults("s-sub", rig.repo, 128_000);
            settings = new AgentSettings(settings.sessionId(), rig.repo, 128_000, 60, 8192, 1, 100, settings.retry(),
                    null, "", settings.sleeper());
            var store = new org.github.lscoughlin.jeorje.core.port.ContextStore() {
                public void append(List<org.github.lscoughlin.jeorje.core.model.Chunk> c) { }
                public List<org.github.lscoughlin.jeorje.core.port.Hit> search(
                        org.github.lscoughlin.jeorje.core.port.HistoryQuery q) { return List.of(); }
                public java.util.Optional<org.github.lscoughlin.jeorje.core.model.Chunk> get(String id) {
                    return java.util.Optional.empty();
                }
                public List<org.github.lscoughlin.jeorje.core.model.Chunk> window(String id, int b, int a) {
                    return List.of();
                }
                public void close() { }
            };
            var sub = new Agent(subId, settings, subLlm, List.of(tool), call -> PermissionPolicy.Decision.ALLOW,
                    store, null, subEvents::add, java.time.Clock.systemUTC());
            for (var turn = 1; turn <= 4; turn++) sub.runTurn("look " + turn);
            var before = subLlm.requests().size();

            rig.session.screen().setForeground(subId);
            var output = rig.session.slash().execute("/compact").output();
            assertTrue(output.contains("Compacted"), output);
            assertEquals(1, subEvents.stream().filter(e -> e instanceof AgentEvent.Compacted).count());
            assertEquals(0, rig.session.agent().turns().size(), "the main agent was not touched");

            sub.runTurn("look 5");
            var requests = subLlm.requests();
            var sizeBefore = ContextBuilder.rawEstimate(requests.get(before - 1));
            var sizeAfter = ContextBuilder.rawEstimate(requests.get(before));
            assertTrue(sizeAfter < sizeBefore, "after " + sizeAfter + " before " + sizeBefore);
        }
    }

    private static final String WRITE = "{\"path\":\"a.txt\",\"content\":\"x\"}";

    @Test
    void allowInTheProjectWritesARuleThatTheNextRunHonors() throws Exception {
        var main = new ScriptedLlmClient(Response.toolCall("w1", "write", WRITE), Response.text("Wrote it."));
        var router = new SessionRig.Router(main);
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start("--trust");
            ready(rig);
            rig.type("write it\r");
            await("prompt", () -> rig.session.screen().pendingPrompt() != null);
            rig.type("p");
            await("turn", () -> rig.session.agent().turns().size() == 1);
            assertEquals("x", Files.readString(rig.repo.resolve("a.txt")));
            var file = ProjectRuleWriter.configFile(rig.repo);
            assertTrue(Files.exists(file));
            var text = Files.readString(file);
            assertTrue(text.contains("tool: write") && text.contains("decision: allow"), text);
            await("notice", () -> chat(rig).contains("Saved a rule to allow write"));

            var options = rig.options("--trust");
            var env = rig.env();
            var loaded = Wiring.loadConfig(options, env);
            var scrubber = new ApiKeyResolver(env.env()).scrubber(loaded.config());
            var use = new ToolUse("c", "write", JsonMapper.builder().build().readTree(WRITE));
            assertEquals(PermissionPolicy.Decision.ALLOW,
                    Wiring.policy(loaded.config(), options, env, rig.repo, "next", scrubber).check(use));
        }
    }

    @Test
    void allowInTheProjectInAnUntrustedProjectDoesNotWriteTheFile() throws Exception {
        var main = new ScriptedLlmClient(Response.toolCall("w1", "write", WRITE), Response.text("Wrote it."),
                Response.toolCall("w2", "write", "{\"path\":\"b.txt\",\"content\":\"y\"}"), Response.text("Again."));
        var router = new SessionRig.Router(main);
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            ready(rig);
            rig.type("write it\r");
            await("prompt", () -> rig.session.screen().pendingPrompt() != null);
            rig.type("p");
            await("turn", () -> rig.session.agent().turns().size() == 1);
            assertFalse(Files.exists(ProjectRuleWriter.configFile(rig.repo)));
            await("notice", () -> chat(rig).contains("not trusted"));
            // The session rule still holds: the second write needs no prompt.
            say(rig, "write again", 2);
            assertEquals("y", Files.readString(rig.repo.resolve("b.txt")));
        }
    }

    @Test
    void allowForTheSessionWritesNoFile() throws Exception {
        var main = new ScriptedLlmClient(Response.toolCall("w1", "write", WRITE), Response.text("Wrote it."));
        var router = new SessionRig.Router(main);
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start("--trust");
            ready(rig);
            rig.type("write it\r");
            await("prompt", () -> rig.session.screen().pendingPrompt() != null);
            rig.type("s");
            await("turn", () -> rig.session.agent().turns().size() == 1);
            assertFalse(Files.exists(ProjectRuleWriter.configFile(rig.repo)));
        }
    }
}
