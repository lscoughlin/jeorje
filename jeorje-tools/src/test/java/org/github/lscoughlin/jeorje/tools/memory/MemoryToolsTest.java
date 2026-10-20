package org.github.lscoughlin.jeorje.tools.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.compaction.ModelRoles;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.permissions.ToolPermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.testkit.InMemoryContextStore;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.tools.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MemoryToolsTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    InMemoryContextStore store;
    TodoTool todo;
    UserQuestionBroker broker;
    CopyOnWriteArrayList<UserQuestion> questions;
    ToolRegistry registry;
    ToolContext context;

    static Chunk chunk(String session, String agent, int turn, String kind, String tool, String text) {
        return new Chunk(agent + ":" + turn + ".0", session, agent, turn, 0, T0.plusSeconds(turn), kind, tool, text);
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryContextStore();
        todo = new TodoTool();
        questions = new CopyOnWriteArrayList<>();
        broker = new UserQuestionBroker(questions::add);
        var tools = MemoryTools.create(new ModelRoles("m", "", ""), "s1",
                id -> id.equals("root") ? "main" : id, todo, broker, null, null);
        registry = ToolRegistry.builder().registerAll(tools).build();
        context = new ToolContext(Path.of("."), new AgentId("root"), CancellationToken.none(), store, Clock.systemUTC());
    }

    ToolResultData call(String tool, String json) {
        return registry.execute(new ToolUse("id", tool, JSON.readTree(json)), context);
    }

    @Test
    void searchCoversAllAgentsButNeverOtherSessions() {
        store.append(List.of(
                chunk("s1", "root", 1, "assistant", null, "the build failed with error alpha"),
                chunk("s1", "sub1", 2, "tool_result", "bash", "alpha error in module X"),
                chunk("other", "sub9", 3, "tool_result", "bash", "alpha error in another session")));
        var result = call("recall", "{\"query\":\"alpha error\"}");
        assertFalse(result.error());
        assertTrue(result.output().contains("root:1.0"));
        assertTrue(result.output().contains("sub1:2.0"));
        assertFalse(result.output().contains("sub9"));
        assertTrue(result.output().contains("main (root)"), "agent name shown");
    }

    @Test
    void filtersNarrowTheSearch() {
        store.append(List.of(
                chunk("s1", "root", 1, "assistant", null, "alpha one"),
                chunk("s1", "sub1", 2, "tool_result", "bash", "alpha two"),
                chunk("s1", "sub1", 5, "tool_result", "grep", "alpha three")));
        var byAgent = call("recall", "{\"query\":\"alpha\",\"filters\":{\"agentId\":\"sub1\"}}").output();
        assertFalse(byAgent.contains("root:1.0"));
        assertTrue(byAgent.contains("sub1:2.0"));
        var byTurn = call("recall", "{\"query\":\"alpha\",\"filters\":{\"fromTurn\":3}}").output();
        assertTrue(byTurn.contains("sub1:5.0"));
        assertFalse(byTurn.contains("sub1:2.0"));
        var byTool = call("recall", "{\"query\":\"alpha\",\"filters\":{\"toolName\":\"grep\"}}").output();
        assertTrue(byTool.contains("sub1:5.0"));
        assertFalse(byTool.contains("sub1:2.0"));
        var byKind = call("recall", "{\"query\":\"alpha\",\"filters\":{\"kind\":\"assistant\"}}").output();
        assertTrue(byKind.contains("root:1.0"));
        assertFalse(byKind.contains("sub1"));
        var limited = call("recall", "{\"query\":\"alpha\",\"limit\":1}").output();
        assertEquals(1, limited.lines().filter(l -> l.contains(" | agent ")).count());
    }

    @Test
    void referenceRuleStoresIdsOnlyAndRecallChunksAreNotHits() {
        store.append(List.of(
                chunk("s1", "root", 1, "tool_result", "bash", "secret payload alpha"),
                chunk("s1", "root", 2, "tool_result", "recall", "alpha recall-ref stored earlier")));
        var output = call("recall", "{\"query\":\"alpha\"}").output();
        assertFalse(output.contains("root:2.0"), "recall chunks are not hits");
        var history = RecallTool.historyText(output);
        assertTrue(history.startsWith(RecallTool.REFERENCE_PREFIX));
        assertFalse(history.contains("\n"));
        assertTrue(history.contains("\"query\":\"alpha\""));
        assertTrue(history.contains("root:1.0"));
        assertFalse(history.contains("secret payload"), "no hit text in history");
        var explicit = call("recall", "{\"query\":\"alpha\",\"filters\":{\"toolName\":\"recall\"}}").output();
        assertTrue(explicit.contains("root:2.0"));
    }

    @Test
    void chunkAndWindowReads() {
        store.append(List.of(
                chunk("s1", "root", 1, "user", null, "first"),
                chunk("s1", "root", 2, "assistant", null, "second full text"),
                chunk("s1", "root", 3, "user", null, "third"),
                chunk("other", "x", 4, "user", null, "foreign")));
        var one = call("recall", "{\"chunkId\":\"root:2.0\"}");
        assertTrue(one.output().contains("second full text"));
        assertFalse(one.output().contains("first"));
        var window = call("recall", "{\"chunkId\":\"root:2.0\",\"before\":1,\"after\":1}").output();
        assertTrue(window.contains("first") && window.contains("third"));
        assertTrue(call("recall", "{\"chunkId\":\"nope\"}").error());
        assertTrue(call("recall", "{\"chunkId\":\"x:4.0\"}").error(), "other session refused");
        assertTrue(call("recall", "{}").error());
        assertTrue(call("recall", "{\"query\":\"a\",\"chunkId\":\"b\"}").error());
    }

    @Test
    void todoKeepsAndPinsTheList() {
        var result = call("todo", "{\"items\":[{\"content\":\"write code\",\"status\":\"in_progress\"},"
                + "{\"content\":\"test\",\"status\":\"pending\"}]}");
        assertFalse(result.error());
        assertTrue(result.output().contains("[~] write code"));
        assertEquals(2, todo.list("root").size());
        assertEquals(0, todo.list("other").size());
        call("todo", "{\"items\":[{\"content\":\"test\",\"status\":\"completed\"}]}");
        assertEquals(1, todo.list("root").size());
        assertTrue(call("todo", "{\"items\":[{\"content\":\"a\",\"status\":\"in_progress\"},"
                + "{\"content\":\"b\",\"status\":\"in_progress\"}]}").error());
        assertTrue(call("todo", "{\"items\":[{\"content\":\"a\",\"status\":\"bogus\"}]}").error());
        assertEquals(1, todo.list("root").size(), "failed call keeps the old list");
        assertFalse(registry.find("todo").orElseThrow().readOnly());
    }

    @Test
    void askUserRoundTrip() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = pool.submit(() -> call("ask_user", "{\"question\":\"Which db?\",\"choices\":[\"pg\",\"mysql\"]}"));
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (questions.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, questions.size());
            var q = questions.getFirst();
            assertEquals("Which db?", q.question());
            assertEquals(List.of("pg", "mysql"), q.choices());
            assertEquals("root", q.agent().value());
            assertFalse(future.isDone());
            assertTrue(broker.answer(q.id(), "pg"));
            var result = future.get(5, TimeUnit.SECONDS);
            assertEquals("User answer: pg", result.output());
            assertFalse(broker.answer(q.id(), "again"));
            assertEquals(0, broker.pendingCount());
        }
    }

    @Test
    void askUserStopsOnCancel() throws Exception {
        var cancel = new CancellationToken();
        var cancellable = new ToolContext(Path.of("."), new AgentId("root"), cancel, store, Clock.systemUTC());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = pool.submit(() -> registry.execute(
                    new ToolUse("id", "ask_user", JSON.readTree("{\"question\":\"q?\"}")), cancellable));
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (questions.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            cancel.cancel();
            assertTrue(future.get(5, TimeUnit.SECONDS).error());
        }
    }

    @Test
    void advisorRegistrationRule() {
        var client = new ScriptedLlmClient(ScriptedLlmClient.Response.text("ok"));
        java.util.function.Function<AgentId, AdvisorContext> contexts = a -> new AdvisorContext("sys", List.of());
        List<Tool> off = MemoryTools.create(new ModelRoles("m", "c", "none"), "s1", null, todo, broker, client, contexts);
        assertEquals(List.of("recall", "todo", "ask_user"), off.stream().map(t -> t.definition().name()).toList());
        List<Tool> byDefault = MemoryTools.create(new ModelRoles("m", "c", ""), "s1", null, todo, broker, client, contexts);
        assertEquals(List.of("recall", "todo", "ask_user", "advisor"),
                byDefault.stream().map(t -> t.definition().name()).toList());
        List<Tool> blank = MemoryTools.create(new ModelRoles("m", "c", "  "), "s1", null, todo, broker, client, contexts);
        assertEquals(4, blank.size());
        List<Tool> with = MemoryTools.create(new ModelRoles("m", "", "~big/model"), "s1", null, todo, broker, client, contexts);
        assertEquals("advisor", with.getLast().definition().name());
        assertTrue(with.getLast().readOnly());
    }

    @Test
    void advisorSendsContextWithoutToolsAndReturnsAdvice() {
        var client = new ScriptedLlmClient(ScriptedLlmClient.Response.text("Do ", "X first."));
        var advisor = new AdvisorTool(client, a -> new AdvisorContext("agent system",
                List.of(Message.user("task"), Message.assistant("[pruned] recall: foo bar"))));
        var reg = ToolRegistry.builder().register(advisor).build();
        var result = reg.execute(new ToolUse("id", "advisor", JSON.readTree("{\"question\":\"Am I done?\"}")), context);
        assertFalse(result.error());
        assertEquals("Do X first.", result.output());
        var request = client.requests().getFirst();
        assertTrue(request.tools().isEmpty());
        assertTrue(request.system().contains("agent system"));
        assertEquals(3, request.messages().size());
        assertTrue(request.messages().getLast().toString().contains("Am I done?"));
        reg.execute(new ToolUse("id2", "advisor", JSON.readTree("{}")), context);
    }
}
