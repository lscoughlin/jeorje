package org.github.lscoughlin.jeorje.tools.story;

import static org.github.lscoughlin.jeorje.tools.story.StoryTestSupport.Brain;
import static org.github.lscoughlin.jeorje.tools.story.StoryTestSupport.PutFile;
import static org.github.lscoughlin.jeorje.tools.story.StoryTestSupport.Responder;
import static org.github.lscoughlin.jeorje.tools.story.StoryTestSupport.Shell;
import static org.github.lscoughlin.jeorje.tools.story.StoryTestSupport.Spec;
import static org.github.lscoughlin.jeorje.tools.story.StoryTestSupport.call;
import static org.github.lscoughlin.jeorje.tools.story.StoryTestSupport.planText;
import static org.github.lscoughlin.jeorje.tools.story.StoryTestSupport.simple;
import static org.github.lscoughlin.jeorje.tools.story.StoryTestSupport.text;
import static org.github.lscoughlin.jeorje.tools.story.StoryTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.agent.Agent;
import org.github.lscoughlin.jeorje.core.agent.AgentInput;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.implement.ImplementOrchestrator;
import org.github.lscoughlin.jeorje.core.implement.ImplementRequest;
import org.github.lscoughlin.jeorje.core.implement.OrchestratorPrompt;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.subagent.AskResult;
import org.github.lscoughlin.jeorje.core.subagent.SubagentLimits;
import org.github.lscoughlin.jeorje.testkit.FakeTool;
import org.github.lscoughlin.jeorje.testkit.InMemoryContextStore;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests of the model loop of the orchestrator: its turns, its answers, and its questions to the user. */
class OrchestratorLoopTest {
    private static final Pattern QUESTION = Pattern.compile(", id (q\\d+)\\]");

    @TempDir Path tmp;
    private final List<ImplementOrchestrator> created = new CopyOnWriteArrayList<>();
    private final List<AgentEvent> events = new CopyOnWriteArrayList<>();
    private final Mind mind = new Mind();
    private final AskUser askUser = new AskUser();
    private ImplementOrchestrator orch;
    private int turnCalls;
    private boolean interactive = true;

    @AfterEach
    void close() {
        askUser.release.countDown();
        created.forEach(ImplementOrchestrator::close);
    }

    // ---- doubles ----

    /** The model of the orchestrator. Requests of workers and of summaries go to the brain. */
    static final class Mind implements LlmClient {
        final Brain brain = new Brain();
        final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile Responder responder = (r, i, c) -> answer(r, "ok");

        @Override
        public Flow.Publisher<LlmEvent> stream(LlmRequest request, CancellationToken cancel) {
            var first = request.messages().isEmpty() ? "" : text(request.messages().get(0));
            if (request.tools().isEmpty() || first.contains("## Story")) return brain.stream(request, cancel);
            requests.add(request);
            threads.add(Thread.currentThread().getName());
            try {
                return new ScriptedLlmClient(responder.next(request, calls.getAndIncrement(), cancel))
                    .stream(request, cancel);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** A fake {@code ask_user}. It counts its calls and waits until the test releases it. */
    static final class AskUser extends RecordTool<AskUser.Input> {
        record Input(@Describe("question") String question) {}

        final AtomicInteger count = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(0);
        volatile CountDownLatch gate = release;

        AskUser() { super("ask_user", "Ask the user.", Input.class, false); }

        AskUser blocking() {
            gate = new CountDownLatch(1);
            return this;
        }

        @Override
        protected ToolResultData run(Input input, ToolContext context) throws Exception {
            count.incrementAndGet();
            started.countDown();
            gate.await(20, TimeUnit.SECONDS);
            return ToolResultData.ok("the user says A");
        }
    }

    static String questionIn(LlmRequest request) {
        for (var i = request.messages().size() - 1; i >= 0; i--) {
            var m = QUESTION.matcher(text(request.messages().get(i)));
            String found = null;
            while (m.find()) found = m.group(1);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * The output of the last tool result, or null when the turn has no tool result yet. The trailing item with the
     * state of the stories can follow the last tool result, so it is skipped.
     */
    static String lastResult(LlmRequest request) {
        for (var i = request.messages().size() - 1; i >= 0; i--) {
            String out = null;
            var plainText = false;
            for (var c : request.messages().get(i).content()) {
                if (c instanceof ToolResult r) out = r.output();
                else if (c instanceof Text t && !t.text().contains("Stories of the run:")) plainText = true;
            }
            if (out != null) return out;
            if (plainText) return null;
        }
        return null;
    }

    static Response answer(LlmRequest request, String answerText) {
        if (lastResult(request) != null) return Response.text("done");
        var question = questionIn(request);
        if (question == null) return Response.text("noted");
        return Response.toolCall("a-" + question, "agent_answer",
            "{\"questionId\":\"" + question + "\",\"text\":\"" + answerText + "\"}");
    }

    static String all(LlmRequest request) {
        var sb = new StringBuilder(request.system() == null ? "" : request.system());
        for (var m : request.messages()) {
            for (var c : m.content()) {
                if (c instanceof Text t) sb.append('\n').append(t.text());
                else if (c instanceof ToolResult r) sb.append('\n').append(r.output());
            }
        }
        return sb.toString();
    }

    private List<Tool> baseTools() {
        var tools = new ArrayList<Tool>(List.of(new PutFile(), new Shell(), askUser,
            FakeTool.returning("spawn_agent", "no"), FakeTool.returning("plan_submit", "no")));
        for (var name : List.of("read", "ls", "glob", "grep", "advisor", "bash", "edit", "write", "patch")) {
            tools.add(FakeTool.returning(name, "x"));
        }
        return tools;
    }

    private ImplementOrchestrator build(String session, Duration askTimeout) {
        var settings = AgentSettings.defaults(session, tmp, 100_000).withSleeper(d -> { });
        var implement = new JeorjeConfig.Implement(1, "", "none", 2, 200, true, "previous");
        var built = ImplementOrchestrator.builder(session, settings, m -> mind, implement)
            .store(new InMemoryContextStore()).agentEvents(events::add)
            .limits(SubagentLimits.defaults().withAskTimeout(askTimeout))
            .mergeRetryInterval(Duration.ofMillis(50)).turnCalls(turnCalls).interactive(interactive)
            .baseTools(baseTools()).workerTools(StoryTools.factory(List.of())).build();
        created.add(built);
        return built;
    }

    private ImplementOrchestrator orchestrator() {
        if (orch == null) orch = build("s1", Duration.ofSeconds(10));
        return orch;
    }

    private AgentId worker(String name, List<AgentInput> inbox) {
        var id = new AgentId(name);
        orchestrator().runtime().registerRoot(id, name, CancellationToken.none(), List.of(),
            a -> PermissionPolicy.Decision.ALLOW, inbox::add, () -> 1);
        return id;
    }

    private CompletableFuture<AskResult> ask(AgentId from, String question) {
        var o = orchestrator();
        return CompletableFuture.supplyAsync(() -> o.runtime().ask(from, o.agentId().value(), question,
            CancellationToken.none()));
    }

    private void runPlan(String file, Brain brain) throws Exception {
        Files.createDirectories(tmp.resolve("plan"));
        var plan = tmp.resolve("plan").resolve("demo.md");
        Files.writeString(plan, planText("approved", null, Spec.of("S1", "First thing", file)));
        brain.script("S1", simple("S1", file, "one"));
        var report = orchestrator().run(ImplementRequest.of(plan));
        assertEquals("done", report.status(), report.render());
    }

    // ---- S1 ----

    @Test
    void aMessageStartsATurnOnTheThreadOfTheOrchestrator() throws Exception {
        var sender = worker("w1", new CopyOnWriteArrayList<>());
        var o = orchestrator();
        var result = o.runtime().message(sender, "w1", o.agentId().value(), "please note the plan changed");
        assertTrue(result.ok(), String.valueOf(result.reason()));
        waitUntil(() -> o.orchestratorTurns() == 1 && !mind.requests.isEmpty());
        assertTrue(all(mind.requests.getFirst()).contains("please note the plan changed"));
        assertEquals("subagent-" + o.agentId(), mind.threads.getFirst());
        assertTrue(mind.requests.getFirst().system().contains("You are the orchestrator"));
    }

    @Test
    void twoQuestionsAtTheSameTimeAreServedOneAfterTheOther() throws Exception {
        var active = new AtomicInteger();
        var maxActive = new AtomicInteger();
        mind.responder = (r, i, c) -> {
            var now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            Thread.sleep(80);
            active.decrementAndGet();
            return answer(r, "reply");
        };
        var first = ask(worker("w1", new CopyOnWriteArrayList<>()), "first question?");
        var second = ask(worker("w2", new CopyOnWriteArrayList<>()), "second question?");
        assertEquals("reply", first.get(20, TimeUnit.SECONDS).answer());
        assertEquals("reply", second.get(20, TimeUnit.SECONDS).answer());
        assertEquals(1, maxActive.get(), "the model calls do not overlap");
        assertEquals(2, orchestrator().orchestratorTurns());
        var threads = new java.util.HashSet<>(mind.threads);
        assertEquals(1, threads.size(), "one thread runs the turns");
    }

    @Test
    void aSubagentResultStartsNoTurnAndTheStateIsInTheNextRequest() throws Exception {
        runPlan("s1.txt", mind.brain);
        var o = orchestrator();
        assertTrue(o.orchestratorMessages().stream().anyMatch(i -> i instanceof AgentInput.SubagentResult),
            "the orchestrator got the result of the worker");
        assertEquals(0, o.orchestratorTurns());
        assertTrue(mind.requests.isEmpty());
        o.submit(new AgentInput.UserInput("how far are we?"));
        waitUntil(() -> !mind.requests.isEmpty());
        var request = all(mind.requests.getFirst());
        assertTrue(request.contains("S1 | First thing | done"), request);
    }

    @Test
    void theOrchestratorHasNoToolThatChangesFiles() throws Exception {
        var o = orchestrator();
        o.submit(new AgentInput.UserInput("hello"));
        waitUntil(() -> !mind.requests.isEmpty());
        var names = mind.requests.getFirst().tools().stream().map(t -> t.name()).toList();
        assertTrue(ImplementOrchestrator.ORCHESTRATOR_TOOLS.containsAll(names), names.toString());
        for (var wanted : List.of("agent_answer", "agent_ask", "agent_message", "agent_status", "story_read",
            "recall", "ask_user", "read", "advisor")) {
            assertTrue(names.contains(wanted), wanted + " in " + names);
        }
        for (var banned : List.of("bash", "edit", "write", "patch", "put_file", "shell", "spawn_agent",
            "plan_submit", "story_done", "task_done")) {
            assertFalse(names.contains(banned), banned + " in " + names);
        }
        assertFalse(o.workerToolNames().contains("ask_user"), "a worker never gets ask_user");
    }

    @Test
    void twoOrchestratorsInOneProcessHaveOwnIdsAndHistories() throws Exception {
        var one = orchestrator();
        var two = build("s1", Duration.ofSeconds(10));
        assertNotEquals(one.agentId(), two.agentId());
        assertTrue(one.runtime().info(one.agentId()).isPresent());
        assertTrue(two.runtime().info(two.agentId()).isPresent());
        assertEquals(one.agentId(), one.orchestratorAgent().id());
        assertEquals(two.agentId(), two.orchestratorAgent().id());
        one.submit(new AgentInput.UserInput("only for the first"));
        waitUntil(() -> one.orchestratorAgent().turns().size() == 1);
        two.submit(new AgentInput.UserInput("only for the second"));
        waitUntil(() -> two.orchestratorAgent().turns().size() == 1);
        assertNotSame(Agent.live(one.agentId()).orElseThrow(), Agent.live(two.agentId()).orElseThrow());
        assertSame(one.orchestratorAgent(), Agent.live(one.agentId()).orElseThrow());
        assertSame(two.orchestratorAgent(), Agent.live(two.agentId()).orElseThrow());
        var firstHistory = one.orchestratorAgent().turns().toString();
        var secondHistory = two.orchestratorAgent().turns().toString();
        assertTrue(firstHistory.contains("only for the first") && !firstHistory.contains("only for the second"));
        assertTrue(secondHistory.contains("only for the second") && !secondHistory.contains("only for the first"));
    }

    @Test
    void aFailedModelCallDoesNotStopTheRun() throws Exception {
        var failing = new java.util.concurrent.atomic.AtomicBoolean(true);
        mind.responder = (r, i, c) -> failing.get() ? Response.failure(500) : answer(r, "fine");
        var o = orchestrator();
        o.submit(new AgentInput.UserInput("boom"));
        waitUntil(() -> events.stream().anyMatch(e -> e instanceof AgentEvent.Error err
            && err.agent().equals(o.agentId())));
        failing.set(false);
        var after = ask(worker("w1", new CopyOnWriteArrayList<>()), "still there?");
        assertEquals("fine", after.get(20, TimeUnit.SECONDS).answer());
        runPlan("s1.txt", mind.brain);
    }

    @Test
    void aTurnHasItsOwnLimitOfModelCalls() throws Exception {
        turnCalls = 3;
        mind.responder = (r, i, c) -> call("c" + i, "agent_status", "{}");
        var o = orchestrator();
        o.submit(new AgentInput.UserInput("loop"));
        waitUntil(() -> events.stream().anyMatch(e -> e instanceof AgentEvent.Error err
            && err.agent().equals(o.agentId()) && err.message().contains("Step limit")));
        assertEquals(3, mind.requests.size());
    }

    // ---- S2 ----

    @Test
    void aStatusQuestionGetsAnswerFromContextAndNeverReachesTheUser() throws Exception {
        runPlan("s1.txt", mind.brain);
        mind.responder = (r, i, c) -> all(r).contains("S1 | First thing | done") ? answer(r, "S1 is done")
            : answer(r, "no context");
        var result = ask(worker("w1", new CopyOnWriteArrayList<>()), "what is the state of S1?")
            .get(20, TimeUnit.SECONDS);
        assertEquals("S1 is done", result.answer());
        assertEquals(0, askUser.count.get());
    }

    @Test
    void aQuestionAboutAnotherStoryUsesStoryRead() throws Exception {
        runPlan("s1.txt", mind.brain);
        var storyReads = new AtomicInteger();
        mind.responder = (r, i, c) -> {
            var last = lastResult(r);
            if (last == null) {
                if (questionIn(r) == null) return Response.text("noted");
                storyReads.incrementAndGet();
                return call("read-1", "story_read", "{\"storyId\":\"S1\"}");
            }
            if (last.equals("Answer sent.")) return Response.text("done");
            return Response.toolCall("a-1", "agent_answer", "{\"questionId\":\"" + questionIn(r)
                + "\",\"text\":\"S1 builds the file s1.txt\"}");
        };
        var result = ask(worker("w1", new CopyOnWriteArrayList<>()), "which interface did S1 build?")
            .get(20, TimeUnit.SECONDS);
        assertEquals("S1 builds the file s1.txt", result.answer());
        assertEquals(1, storyReads.get());
        assertTrue(all(mind.requests.get(1)).contains("Create the file of S1"), "the request has the story text");
        assertEquals(0, askUser.count.get());
    }

    @Test
    void aTurnWithoutAnAnswerGivesTheFallbackAtOnce() throws Exception {
        mind.responder = (r, i, c) -> Response.text("I will think about it.");
        var start = System.nanoTime();
        var result = ask(worker("w1", new CopyOnWriteArrayList<>()), "what now?").get(5, TimeUnit.SECONDS);
        assertTrue(result.ok(), String.valueOf(result.reason()));
        assertEquals(OrchestratorPrompt.NO_ANSWER, result.answer());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0);
        assertTrue(result.answer().contains("best choice"));
        assertTrue(result.answer().contains("assumption"));
    }

    @Test
    void aQuestionThatArrivesWhileTheOrchestratorWaitsForTheUserGetsTheFallbackAtOnce() throws Exception {
        askUser.blocking();
        mind.responder = (r, i, c) -> {
            var last = lastResult(r);
            if (last == null) {
                var q = questionIn(r);
                return q == null ? Response.text("noted") : Response.toolCall("a", "agent_answer",
                    "{\"questionId\":\"" + q + "\",\"text\":\"Wait for the decision. Use your best choice.\"}");
            }
            if (last.equals("Answer sent.")) return call("u", "ask_user", "{\"question\":\"A or B?\"}");
            return Response.text("done");
        };
        var first = ask(worker("w1", new CopyOnWriteArrayList<>()), "A or B?").get(20, TimeUnit.SECONDS);
        assertEquals("Wait for the decision. Use your best choice.", first.answer());
        assertTrue(askUser.started.await(20, TimeUnit.SECONDS));
        var start = System.nanoTime();
        var second = ask(worker("w2", new CopyOnWriteArrayList<>()), "and what about C?").get(5, TimeUnit.SECONDS);
        assertEquals(OrchestratorPrompt.WAITING_FOR_USER, second.answer());
        assertEquals("The orchestrator waits for the user. Continue with your best choice.", second.answer());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0);
        assertEquals(1, askUser.count.get());
        askUser.gate.countDown();
    }

    @Test
    void aQuestionThatGetsNoAnswerInTheTimeOfTheAskerGetsTheFallback() throws Exception {
        var hold = new CountDownLatch(1);
        mind.responder = (r, i, c) -> {
            if (i == 0) hold.await(20, TimeUnit.SECONDS);
            return answer(r, "late");
        };
        var o = build("s2", Duration.ofMillis(300));
        var asker = new AgentId("w9");
        o.runtime().registerRoot(asker, "w9", CancellationToken.none(), List.of(),
            a -> PermissionPolicy.Decision.ALLOW, in -> { }, () -> 1);
        var blocker = CompletableFuture.supplyAsync(() -> o.runtime().ask(asker, o.agentId().value(), "slow?",
            CancellationToken.none()));
        var asker2 = new AgentId("w8");
        o.runtime().registerRoot(asker2, "w8", CancellationToken.none(), List.of(),
            a -> PermissionPolicy.Decision.ALLOW, in -> { }, () -> 1);
        var waiting = CompletableFuture.supplyAsync(() -> o.runtime().ask(asker2, o.agentId().value(), "queued?",
            CancellationToken.none()));
        assertEquals(OrchestratorPrompt.NO_ANSWER, waiting.get(10, TimeUnit.SECONDS).answer());
        assertEquals(OrchestratorPrompt.NO_ANSWER, blocker.get(10, TimeUnit.SECONDS).answer());
        hold.countDown();
    }

    @Test
    void aConflictAsksTheUserGivesAHoldingAnswerAndSendsTheDecisionLater() throws Exception {
        var plan = tmp.resolve("plan");
        Files.createDirectories(plan);
        Files.writeString(plan.resolve("demo.md"), planText("approved", null, Spec.of("S1", "First thing", "s1.txt")));
        var decisionSent = new CountDownLatch(1);
        var tail = simple("S1", "s1.txt", "one");
        mind.brain.worker("S1", (r, i, c) -> {
            if (i == 0) {
                return call("w-ask", "agent_ask", "{\"to\":\"orchestrator\",\"question\":\"two criteria conflict\"}");
            }
            if (i == 1) {
                decisionSent.await(20, TimeUnit.SECONDS);
                return tail[0];
            }
            return i - 1 < tail.length ? tail[i - 1] : Response.text("done");
        });
        mind.responder = (r, i, c) -> {
            var last = lastResult(r);
            if (last == null) {
                var q = questionIn(r);
                return q == null ? Response.text("noted") : Response.toolCall("a", "agent_answer",
                    "{\"questionId\":\"" + q + "\",\"text\":\"Hold on. Do the other tasks. Call story_blocked if stuck.\"}");
            }
            if (last.equals("Answer sent.")) return call("u", "ask_user", "{\"question\":\"Which criterion wins?\"}");
            if (last.contains("the user says")) {
                return call("m", "agent_message", "{\"to\":\"S1\",\"text\":\"Decision of the user: criterion A wins.\"}");
            }
            decisionSent.countDown();
            return Response.text("done");
        };
        var report = orchestrator().run(ImplementRequest.of(plan.resolve("demo.md")));
        assertEquals("done", report.status(), report.render());
        assertEquals(1, askUser.count.get());
        var worker = mind.brain.of("S1");
        assertTrue(worker.get(1).messages().stream().anyMatch(m -> m.content().stream().anyMatch(
            c -> c instanceof ToolResult t && t.output().contains("Hold on. Do the other tasks"))),
            "the worker got the holding answer first");
        var seen = all(worker.get(2));
        assertTrue(seen.contains("Decision of the user: criterion A wins."), "the worker sees the late decision");
        var asked = all(mind.requests.getFirst());
        assertTrue(asked.contains("[question from S1 First thing (S1), id q"), asked);
    }

    @Test
    void withNoUserTheOrchestratorHasNoAskUserAndTheReportListsTheQuestionsAndTheAssumptions() throws Exception {
        interactive = false;
        var plan = tmp.resolve("plan");
        Files.createDirectories(plan);
        Files.writeString(plan.resolve("demo.md"), planText("approved", null, Spec.of("S1", "First thing", "s1.txt")));
        var tail = simple("S1", "s1.txt", "one");
        mind.brain.worker("S1", (r, i, c) -> switch (i) {
            case 0 -> call("w-status", "agent_ask", "{\"to\":\"orchestrator\",\"question\":\"what are you doing?\"}");
            case 1 -> call("w-ask", "agent_ask", "{\"to\":\"orchestrator\",\"question\":\"two criteria conflict\"}");
            default -> i - 2 < tail.length ? tail[i - 2] : Response.text("done");
        });
        mind.brain.summary("S1", "Outcome: completed\nDone: work\nFiles: s1.txt\nBranch: merged\n"
            + "Decisions: Chose criterion A because it is stricter.\nVerification: ok\nOpen issues: none\nKeywords: Story, Goal, Tasks");
        mind.responder = (r, i, c) -> {
            if (lastResult(r) != null) return Response.text("done");
            var q = questionIn(r);
            if (q == null) return Response.text("noted");
            var conflict = all(r).contains("two criteria conflict");
            return Response.toolCall("a-" + q, "agent_answer", "{\"questionId\":\"" + q + "\",\"text\":\""
                + (conflict ? "Continue with your best choice." : "S1 works on its file.") + "\""
                + (conflict ? ",\"needsPerson\":true" : "") + "}");
        };
        var report = orchestrator().run(ImplementRequest.of(plan.resolve("demo.md")));
        assertEquals("done", report.status(), report.render());
        assertEquals(0, askUser.count.get());
        assertTrue(mind.requests.stream().allMatch(r -> r.tools().stream().noneMatch(t -> t.name().equals("ask_user"))));
        assertTrue(mind.requests.getFirst().system().contains("No user is present"));
        assertFalse(mind.requests.getFirst().system().contains("Call ask_user"));
        assertEquals(1, report.personQuestions().size(), report.render());
        assertTrue(report.personQuestions().getFirst().contains("two criteria conflict"), report.render());
        assertTrue(report.personQuestions().getFirst().contains("Continue with your best choice."), report.render());
        assertEquals(List.of("S1: Chose criterion A because it is stricter."), report.assumptions());
        assertTrue(report.render().contains("Questions that needed a person:"), report.render());
        assertTrue(report.render().contains("Assumptions of the workers:"), report.render());
    }

    private static void assertSame(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertSame(expected, actual);
    }
}
