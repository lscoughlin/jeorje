package org.github.lscoughlin.jeorje.testkit;

import static org.junit.jupiter.api.Assertions.*;

import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.Filters;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.github.lscoughlin.jeorje.core.port.Syntax;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StubsTest {
    private static final LlmRequest REQUEST = new LlmRequest("sys", List.of(Message.user("hi")), List.of(), 100);

    private record Result(List<LlmEvent> events, Throwable error, boolean completed) {}

    private static Result collect(Flow.Publisher<LlmEvent> publisher) {
        var events = new ArrayList<LlmEvent>();
        var error = new Throwable[1];
        var done = new boolean[1];
        publisher.subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(LlmEvent e) { events.add(e); }
            public void onError(Throwable t) { error[0] = t; }
            public void onComplete() { done[0] = true; }
        });
        return new Result(events, error[0], done[0]);
    }

    private static ToolContext context(CancellationToken token) {
        return new ToolContext(null, null, token, null, new ManualClock());
    }

    private static Chunk chunk(String id, int turn, String text) {
        return new Chunk(id, "s", "a", turn, 0, Instant.EPOCH, "text", null, text);
    }

    @Test
    void scriptedClientPlaysToolCallThenAnswer() {
        var client = new ScriptedLlmClient(
            ScriptedLlmClient.Response.toolCall("t1", "read", "{\"path\":\"a\"}"),
            ScriptedLlmClient.Response.text("Done", " now"));
        var first = collect(client.stream(REQUEST, CancellationToken.none()));
        assertTrue(first.completed());
        assertInstanceOf(LlmEvent.ToolUseStart.class, first.events().getFirst());
        var json = first.events().stream().filter(e -> e instanceof LlmEvent.ToolUseInput)
            .map(e -> ((LlmEvent.ToolUseInput) e).jsonFragment()).reduce("", String::concat);
        assertEquals("{\"path\":\"a\"}", json);
        assertEquals(new LlmEvent.MessageStop("tool_use"), first.events().getLast());
        var second = collect(client.stream(REQUEST, CancellationToken.none()));
        assertEquals(new LlmEvent.TextDelta("Done"), second.events().getFirst());
        assertEquals(new LlmEvent.MessageStop("end_turn"), second.events().getLast());
        assertEquals(2, client.requests().size());
        assertSame(REQUEST, client.requests().getFirst());
    }

    @Test
    void scriptedClientFailsWithStatusAndWhenExhausted() {
        var client = new ScriptedLlmClient(ScriptedLlmClient.Response.failure(429));
        var failed = collect(client.stream(REQUEST, CancellationToken.none()));
        assertEquals(429, ((ScriptedLlmClient.HttpFailure) failed.error()).status());
        var exhausted = collect(client.stream(REQUEST, CancellationToken.none()));
        assertInstanceOf(IllegalStateException.class, exhausted.error());
    }

    @Test
    void scriptedClientStopsWhenCancelled() {
        var client = new ScriptedLlmClient(ScriptedLlmClient.Response.text("a"));
        var token = new CancellationToken();
        token.cancel();
        var result = collect(client.stream(REQUEST, token));
        assertTrue(result.events().isEmpty());
        assertTrue(result.completed());
    }

    @Test
    void embedderIsDeterministic() {
        var embedder = new StubEmbedder();
        assertEquals(384, embedder.dimensions());
        assertArrayEquals(embedder.embed("hello"), embedder.embed("hello"));
        assertFalse(Arrays.equals(embedder.embed("hello"), embedder.embed("world")));
        assertEquals(384, embedder.embed("x").length);
        var batch = embedder.embedBatch(List.of("a", "b"));
        assertArrayEquals(embedder.embed("b"), batch.get(1));
    }

    @Test
    void manualClockMovesOnlyOnDemand() {
        var clock = new ManualClock(Instant.parse("2026-02-01T00:00:00Z"));
        var t0 = clock.instant();
        assertEquals(t0, clock.instant());
        clock.advance(Duration.ofMinutes(5));
        assertEquals(t0.plusSeconds(300), clock.instant());
        clock.set(Instant.EPOCH);
        assertEquals(Instant.EPOCH, clock.millis() == 0 ? Instant.EPOCH : null);
    }

    @Test
    void fixedPolicyReturnsDecisionAndRecords() {
        var policy = new FixedPolicy(Decision.DENY);
        var use = new ToolUse("1", "bash", null);
        assertEquals(Decision.DENY, policy.check(use));
        assertEquals(List.of(use), policy.checked());
        assertEquals(Decision.ALLOW, FixedPolicy.allow().check(use));
        assertEquals(Decision.ASK, FixedPolicy.ask().check(use));
    }

    @Test
    void fakeToolReturnsAndThrows() throws Exception {
        var input = JsonMapper.builder().build().createObjectNode().put("a", 1);
        var ok = FakeTool.returning("t", "result");
        var data = ok.execute(input, context(CancellationToken.none()));
        assertEquals("result", data.output());
        assertFalse(data.error());
        assertEquals("t", ok.definition().name());
        assertEquals(List.of(input), ok.inputs());
        var bad = FakeTool.throwing("t", new java.io.IOException("boom"));
        assertThrows(java.io.IOException.class, () -> bad.execute(input, context(CancellationToken.none())));
    }

    @Test
    void fakeToolBlocksUntilCancelled() throws Exception {
        var tool = FakeTool.blocking("slow");
        var token = new CancellationToken();
        var failure = new Throwable[1];
        var thread = Thread.ofVirtual().start(() -> {
            try {
                tool.execute(null, context(token));
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        assertTrue(tool.awaitStarted(2000));
        assertTrue(thread.isAlive());
        token.cancel();
        thread.join(2000);
        assertInstanceOf(java.util.concurrent.CancellationException.class, failure[0]);
    }

    @Test
    void tempWorkspaceWritesAndCleansUp() {
        java.nio.file.Path root;
        try (var ws = new TempWorkspace()) {
            root = ws.root();
            ws.write("a/b/c.txt", "hello");
            assertEquals("hello", ws.read("a/b/c.txt"));
            assertTrue(ws.exists("a/b"));
            assertTrue(java.nio.file.Files.isDirectory(ws.mkdir("d")));
        }
        assertFalse(java.nio.file.Files.exists(root));
    }

    @Test
    void contextStoreSearchesGetsAndWindows() {
        try (var store = new InMemoryContextStore()) {
            store.append(List.of(chunk("a:1.0", 1, "alpha beta"), chunk("a:2.0", 2, "beta gamma"),
                chunk("a:3.0", 3, "delta")));
            var hits = store.search(new HistoryQuery("alpha beta", Syntax.TEXT, Filters.none(), 10));
            assertEquals("a:1.0", hits.getFirst().chunk().chunkId());
            assertEquals(2, hits.size());
            assertEquals(1, store.search(new HistoryQuery("beta", Syntax.TEXT, Filters.none(), 1)).size());
            var filters = new Filters(java.util.Set.of(), java.util.OptionalInt.of(2), java.util.OptionalInt.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Set.of(), java.util.Set.of());
            assertEquals(1, store.search(new HistoryQuery("beta", Syntax.TEXT, filters, 10)).size());
            assertTrue(store.get("a:2.0").isPresent());
            assertTrue(store.get("zzz").isEmpty());
            assertEquals(3, store.window("a:2.0", 1, 1).size());
            assertEquals(2, store.window("a:1.0", 5, 1).size());
        }
    }
}
