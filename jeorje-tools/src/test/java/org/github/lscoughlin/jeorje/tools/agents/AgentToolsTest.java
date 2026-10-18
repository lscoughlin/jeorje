package org.github.lscoughlin.jeorje.tools.agents;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.github.lscoughlin.jeorje.core.agent.AgentInput;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.subagent.SpawnMode;
import org.github.lscoughlin.jeorje.core.subagent.SpawnRequest;
import org.github.lscoughlin.jeorje.core.subagent.SubagentLimits;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.testkit.InMemoryContextStore;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.github.lscoughlin.jeorje.tools.registry.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class AgentToolsTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String WORK = "I wrote Parser.java with a regex parser.";
    static final String VALID = """
        Outcome: completed
        Done: Wrote the parser.
        Files: Parser.java
        Branch: none
        Decisions: Used a regex because it is short.
        Verification: ran mvn test, it passed.
        Open issues: none
        Keywords: Parser.java, regex, parser""";

    @TempDir Path dir;
    final Map<String, LlmClient> models = new ConcurrentHashMap<>();
    final List<AgentInput> rootInput = new CopyOnWriteArrayList<>();
    final AgentId root = new AgentId("root");
    InMemoryContextStore store;
    SubagentRuntime runtime;
    ToolRegistry registry;
    ToolContext context;

    @BeforeEach
    void setUp() {
        store = new InMemoryContextStore();
        var settings = AgentSettings.defaults("s1", dir, 100_000).withSleeper(d -> { });
        runtime = SubagentRuntime.builder("s1", settings, models::get).store(store)
            .limits(SubagentLimits.defaults().withAskTimeout(Duration.ofSeconds(5))).build();
        var tools = AgentTools.install(runtime, "s1");
        registry = ToolRegistry.builder().registerAll(tools).build();
        runtime.registerRoot(root, "root", CancellationToken.none(), tools, a -> PermissionPolicy.Decision.ALLOW,
            rootInput::add, () -> 1);
        context = new ToolContext(dir, root, CancellationToken.none(), store, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() { runtime.close(); }

    ToolResultData call(String tool, String json) throws Exception {
        return registry.execute(new ToolUse("u-" + tool, tool, JSON.readTree(json)), context);
    }

    static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("condition not met in 10 s");
            Thread.sleep(5);
        }
    }

    static String requestText(ScriptedLlmClient llm, int index) {
        var sb = new StringBuilder();
        for (var m : llm.requests().get(index).messages()) m.content().forEach(c -> sb.append('\n').append(c));
        return sb.toString();
    }

    // ---- definitions ----

    @Test
    void installMakesTheToolsAndSharesAllButSpawn() {
        assertEquals(List.of("spawn_agent", "agent_status", "agent_message", "agent_ask", "agent_answer", "recall"),
            registry.names());
        var schema = registry.find("spawn_agent").orElseThrow().definition().inputSchema();
        assertEquals("task", schema.path("required").get(1).asString());
        assertEquals("wait", schema.path("properties").path("mode").path("enum").get(1).asString());
        assertEquals("array", schema.path("properties").path("tools").path("type").asString());
    }

    @Test
    void inputIsValidated() throws Exception {
        var result = call("spawn_agent", "{\"name\":\"w\"}");
        assertTrue(result.error());
        assertTrue(result.output().contains("Invalid input"), result.output());
        assertTrue(call("spawn_agent", "{\"name\":\"w\",\"task\":\"t\",\"mode\":\"later\"}").error());
        assertTrue(call("agent_ask", "{\"to\":\"x\"}").error());
    }

    // ---- spawn_agent ----

    @Test
    void backgroundSpawnReturnsTheAgentIdAndThenDeliversTheResult() throws Exception {
        models.put("m", new ScriptedLlmClient(Response.text(WORK), Response.text(VALID)));
        var result = call("spawn_agent", "{\"name\":\"worker\",\"task\":\"Fix Parser.java regex\",\"model\":\"m\"}");
        assertFalse(result.error(), result.output());
        assertTrue(result.output().contains("agent id sub-1"), result.output());
        waitUntil(() -> !rootInput.isEmpty());
        var input = assertInstanceOf(AgentInput.SubagentResult.class, rootInput.getFirst());
        assertTrue(input.text().contains("Outcome: completed"));
    }

    @Test
    void waitSpawnReturnsTheSummaryAndSavesTheChunk() throws Exception {
        models.put("m", new ScriptedLlmClient(Response.text(WORK), Response.text(VALID)));
        var result = call("spawn_agent",
            "{\"name\":\"worker\",\"task\":\"Fix Parser.java regex\",\"model\":\"m\",\"mode\":\"wait\"}");
        assertFalse(result.error(), result.output());
        assertTrue(result.output().contains("ended with the outcome completed"), result.output());
        assertTrue(result.output().contains("Keywords: Parser.java, regex, parser"));
        assertTrue(rootInput.isEmpty(), "wait mode sends no input event");
        var summaries = store.all().stream().filter(c -> c.kind().equals("subagent_summary")).toList();
        assertEquals(1, summaries.size());
        assertEquals("root", summaries.getFirst().agentId());
        assertEquals("sub-1", summaries.getFirst().sourceAgentId());
    }

    @Test
    void waitSpawnOfAFailedSubagentIsAnError() throws Exception {
        models.put("m", new ScriptedLlmClient(Response.failure(400)));
        var result = call("spawn_agent",
            "{\"name\":\"worker\",\"task\":\"Fix Parser.java regex\",\"model\":\"m\",\"mode\":\"wait\"}");
        assertTrue(result.error());
        assertTrue(result.output().contains("outcome failed"), result.output());
        assertTrue(result.output().contains("Outcome: failed"));
    }

    @Test
    void spawnRefusalsBecomeErrorResults() throws Exception {
        var unknown = call("spawn_agent", "{\"name\":\"w\",\"task\":\"t\",\"type\":\"nope\"}");
        assertTrue(unknown.error());
        assertTrue(unknown.output().contains("Unknown agent type"), unknown.output());
        var blank = call("spawn_agent", "{\"name\":\" \",\"task\":\"t\"}");
        assertTrue(blank.error());
        assertTrue(blank.output().startsWith("spawn_agent failed"));
    }

    @Test
    void droppedToolsAreReported() throws Exception {
        models.put("m", new ScriptedLlmClient(Response.text(WORK), Response.text(VALID)));
        var result = call("spawn_agent", "{\"name\":\"worker\",\"task\":\"Fix Parser.java regex\","
            + "\"model\":\"m\",\"tools\":[\"agent_status\",\"bash\"]}");
        assertFalse(result.error());
        assertTrue(result.output().contains("were not given: bash"), result.output());
        assertEquals(List.of("agent_status", "agent_message", "agent_ask", "agent_answer", "recall"),
            runtime.toolNames(new AgentId("sub-1")));
    }

    @Test
    void depthLimitAppliesToTheTool() throws Exception {
        // A parent chain a -> b with the limit of 2 is fine. A third level is refused.
        var hang = new CountDownLatch(1);
        models.put("block", blockingClient(hang));
        var a = runtime.spawn(root, SpawnRequest.of("a", "task").withModel("block"));
        var b = runtime.spawn(a, SpawnRequest.of("b", "task").withModel("block"));
        var deep = new ToolContext(dir, b, CancellationToken.none(), store, Clock.systemUTC());
        var result = registry.execute(new ToolUse("u", "spawn_agent",
            JSON.readTree("{\"name\":\"c\",\"task\":\"t\"}")), deep);
        assertTrue(result.error());
        assertTrue(result.output().contains("Depth limit"), result.output());
        hang.countDown();
    }

    private static LlmClient blockingClient(CountDownLatch release) {
        return (request, cancel) -> subscriber -> subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            @Override public void request(long n) {
                cancel.onCancel(release::countDown);
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                subscriber.onComplete();
            }

            @Override public void cancel() { }
        });
    }

    // ---- agent_status ----

    @Test
    void statusListsAgents() throws Exception {
        var release = new CountDownLatch(1);
        models.put("block", blockingClient(release));
        runtime.spawn(root, SpawnRequest.of("worker", "Do the long task").withModel("block").withStoryId("B10"));
        waitUntil(() -> runtime.status().size() == 2);
        var result = call("agent_status", "{}");
        assertFalse(result.error());
        assertTrue(result.output().contains("root | id root | RUNNING"), result.output());
        assertTrue(result.output().contains("worker | id sub-1"), result.output());
        assertTrue(result.output().contains("story B10"));
        assertTrue(result.output().contains("parent root"));
        assertTrue(result.output().contains("task: Do the long task"));
        release.countDown();
    }

    // ---- ask, answer, message ----

    @Test
    void askAndAnswerGoThroughTheTools() throws Exception {
        var llm = new ScriptedLlmClient(
            Response.toolCall("c1", "agent_ask", "{\"to\":\"root\",\"question\":\"which file?\"}"),
            Response.text(WORK), Response.text(VALID));
        models.put("m", llm);
        var spawned = call("spawn_agent", "{\"name\":\"asker\",\"task\":\"Fix Parser.java regex\",\"model\":\"m\"}");
        assertFalse(spawned.error());
        waitUntil(() -> rootInput.stream().anyMatch(i -> i instanceof AgentInput.PeerMessage));
        var question = (AgentInput.PeerMessage) rootInput.getFirst();
        assertTrue(question.text().contains("[question from asker (sub-1), id q1]\nwhich file?"), question.text());
        var answered = call("agent_answer", "{\"questionId\":\"q1\",\"text\":\"Parser.java\"}");
        assertFalse(answered.error(), answered.output());
        waitUntil(() -> llm.callCount() == 3);
        assertTrue(requestText(llm, 1).contains("Parser.java"), requestText(llm, 1));
        assertTrue(call("agent_answer", "{\"questionId\":\"q1\",\"text\":\"again\"}").error(), "closed question");
    }

    @Test
    void askFailuresNameTheReason() throws Exception {
        var unknown = call("agent_ask", "{\"to\":\"nobody\",\"question\":\"q?\"}");
        assertTrue(unknown.error());
        assertTrue(unknown.output().startsWith("agent_ask failed: unknown agent"), unknown.output());
        runtime.registerPending("S5");
        var notStarted = call("agent_ask", "{\"to\":\"S5\",\"question\":\"q?\"}");
        assertTrue(notStarted.output().contains("not started"), notStarted.output());
        assertTrue(call("agent_message", "{\"to\":\"S5\",\"text\":\"hi\"}").output().contains("not started"));
        assertTrue(call("agent_answer", "{\"questionId\":\"q9\",\"text\":\"x\"}").error());
    }

    @Test
    void askToAnEndedAgentReturnsTheSummaryWithKeywords() throws Exception {
        models.put("m", new ScriptedLlmClient(Response.text(WORK), Response.text(VALID)));
        call("spawn_agent", "{\"name\":\"worker\",\"task\":\"Fix Parser.java regex\",\"model\":\"m\",\"mode\":\"wait\"}");
        var result = call("agent_ask", "{\"to\":\"worker\",\"question\":\"why a regex?\"}");
        assertTrue(result.error());
        assertTrue(result.output().startsWith("agent_ask failed: ended"), result.output());
        assertTrue(result.output().contains("Keywords: Parser.java, regex, parser"), result.output());
        var message = call("agent_message", "{\"to\":\"worker\",\"text\":\"thanks\"}");
        assertTrue(message.error());
        assertTrue(message.output().contains("ended"));
    }

    @Test
    void askToAWaitingParentFromItsChildIsADeadlock() throws Exception {
        var release = new CountDownLatch(1);
        models.put("block", blockingClient(release));
        var child = runtime.spawn(root, SpawnRequest.of("child", "task").withModel("block").withMode(SpawnMode.WAIT));
        var childContext = new ToolContext(dir, child, CancellationToken.none(), store, Clock.systemUTC());
        var result = registry.execute(new ToolUse("u", "agent_ask",
            JSON.readTree("{\"to\":\"root\",\"question\":\"q?\"}")), childContext);
        assertTrue(result.error());
        assertTrue(result.output().startsWith("agent_ask failed: deadlock"), result.output());
        release.countDown();
    }

    @Test
    void messageReachesTheAgent() throws Exception {
        var peer = new AgentId("peer");
        runtime.registerRoot(peer, "peer", CancellationToken.none(), List.of(), a -> PermissionPolicy.Decision.ALLOW,
            rootInput::add, () -> 1);
        var result = call("agent_message", "{\"to\":\"peer\",\"text\":\"use tabs\"}");
        assertFalse(result.error(), result.output());
        var input = assertInstanceOf(AgentInput.PeerMessage.class, rootInput.getFirst());
        assertTrue(input.text().contains("use tabs"));
    }

    // ---- recall ----

    @Test
    void subagentRecallSearchesTheFullSessionIndex() throws Exception {
        store.append(List.of(new Chunk("root:1.0", "s1", "root", 1, 0, Instant.parse("2026-01-01T00:00:00Z"),
            "assistant", null, "ASSISTANT: the secret code is alpha7")));
        store.append(List.of(new Chunk("other:1.0", "other-session", "root", 1, 0, Instant.parse("2026-01-01T00:00:00Z"),
            "assistant", null, "ASSISTANT: alpha7 in another session")));
        var llm = new ScriptedLlmClient(
            Response.toolCall("c1", "recall", "{\"query\":\"alpha7\"}"),
            Response.toolCall("c2", "recall", "{\"query\":\"alpha7\",\"filters\":{\"agentId\":\"sub-1\"}}"),
            Response.text(WORK), Response.text(VALID));
        models.put("m", llm);
        var result = call("spawn_agent",
            "{\"name\":\"worker\",\"task\":\"Find the code and fix Parser.java regex\",\"model\":\"m\",\"mode\":\"wait\"}");
        assertFalse(result.error(), result.output());
        var first = requestText(llm, 1);
        assertTrue(first.contains("root:1.0"), "a hit from the parent: " + first);
        assertTrue(first.contains("agent root"), "the hit shows the source agent: " + first);
        assertFalse(first.contains("other:1.0"), "no chunk of another session");
        var lastResult = llm.requests().get(2).messages().getLast().content().getFirst().toString();
        assertFalse(lastResult.contains("root:1.0"), "the agentId filter narrows the search: " + lastResult);
    }

    @Test
    void parentFindsDetailsOfTheSummaryWithRecall() throws Exception {
        models.put("m", new ScriptedLlmClient(Response.text(WORK), Response.text(VALID)));
        call("spawn_agent", "{\"name\":\"worker\",\"task\":\"Fix Parser.java regex\",\"model\":\"m\",\"mode\":\"wait\"}");
        var found = call("recall", "{\"query\":\"Parser.java\",\"filters\":{\"kind\":\"subagent_summary\"}}");
        assertFalse(found.error(), found.output());
        assertTrue(found.output().contains("subagent_summary"), found.output());
    }

    @Test
    void agentToolsAreNotSharedWithSpawn() throws Exception {
        var release = new CountDownLatch(1);
        models.put("block", blockingClient(release));
        var child = runtime.spawn(root, SpawnRequest.of("child", "task").withModel("block"));
        assertFalse(runtime.toolNames(child).contains("spawn_agent"));
        assertTrue(runtime.toolNames(child).containsAll(List.of("recall", "agent_ask", "agent_answer",
            "agent_message", "agent_status")));
        release.countDown();
    }

    @Test
    void toolsAreNotReadOnlyWhereTheyChangeState() {
        assertTrue(registry.find("agent_status").orElseThrow().readOnly());
        for (var name : List.of("spawn_agent", "agent_message", "agent_ask", "agent_answer")) {
            Tool tool = registry.find(name).orElseThrow();
            assertFalse(tool.readOnly(), name);
        }
    }
}
