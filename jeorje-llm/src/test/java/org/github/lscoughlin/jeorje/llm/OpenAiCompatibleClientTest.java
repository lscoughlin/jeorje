package org.github.lscoughlin.jeorje.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import org.github.lscoughlin.jeorje.core.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCompatibleClientTest {

    private static final String KEY = "sk-test-secret-key-123";
    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> auths = new CopyOnWriteArrayList<>();
    private final List<String> referers = new CopyOnWriteArrayList<>();
    private final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile ExchangeHandler handler;

    interface ExchangeHandler {
        void handle(int call, HttpExchange exchange) throws IOException;
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/chat/completions", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auths.add(ex.getRequestHeaders().getFirst("Authorization"));
            referers.add(ex.getRequestHeaders().getFirst("HTTP-Referer"));
            handler.handle(calls.incrementAndGet(), ex);
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private OpenAiCompatibleClient client() {
        var settings = OpenAiSettings.of("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-model", KEY, Map.of("HTTP-Referer", "https://example.test", "X-Title", "jeorje"));
        return new OpenAiCompatibleClient(settings, HttpClient.newHttpClient(), sleeps::add,
                RandomGenerator.getDefault());
    }

    private static void sse(HttpExchange ex, String... chunks) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        try (var out = ex.getResponseBody()) {
            for (var chunk : chunks) {
                out.write(((chunk.startsWith(":") ? chunk : "data: " + chunk) + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    private static void status(HttpExchange ex, int code, String body, String... headers) throws IOException {
        for (var i = 0; i < headers.length; i += 2) ex.getResponseHeaders().add(headers[i], headers[i + 1]);
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (var out = ex.getResponseBody()) { out.write(bytes); }
    }

    private static String textChunk(String text) {
        return "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text + "\"}}]}";
    }

    private static final String STOP = "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";
    private static final String USAGE =
            "{\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":7,\"total_tokens\":19}}";

    private static LlmRequest simple() {
        return new LlmRequest("be brief", List.of(Message.user("hi")), List.of(), 100);
    }

    private List<LlmEvent> run(LlmRequest request) throws Exception {
        return run(request, CancellationToken.none());
    }

    private List<LlmEvent> run(LlmRequest request, CancellationToken token) throws Exception {
        var done = new CompletableFuture<List<LlmEvent>>();
        var events = new ArrayList<LlmEvent>();
        client().stream(request, token).subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(LlmEvent e) { events.add(e); }
            public void onError(Throwable t) { done.completeExceptionally(t); }
            public void onComplete() { done.complete(events); }
        });
        return done.get(10, TimeUnit.SECONDS);
    }

    private static LlmException failureOf(Throwable t) {
        while (t != null && !(t instanceof LlmException)) t = t.getCause();
        return (LlmException) t;
    }

    @Test
    void streamsText() throws Exception {
        handler = (n, ex) -> sse(ex, ": comment", textChunk("Hel"), textChunk("lo"), STOP, USAGE, "[DONE]");
        var events = run(simple());
        assertEquals(List.of(new LlmEvent.TextDelta("Hel"), new LlmEvent.TextDelta("lo"),
                new LlmEvent.Usage(12, 7), new LlmEvent.MessageStop("stop")), events);
        assertEquals("Bearer " + KEY, auths.get(0));
        assertEquals("https://example.test", referers.get(0));
        var body = json.readTree(bodies.get(0));
        assertTrue(body.get("stream").asBoolean());
        assertTrue(body.get("stream_options").get("include_usage").asBoolean());
        assertEquals("test-model", body.get("model").asString());
        assertEquals("system", body.get("messages").get(0).get("role").asString());
        assertEquals("user", body.get("messages").get(1).get("role").asString());
        assertFalse(body.has("tools"));
    }

    @Test
    void assemblesToolCallSplitOverFragments() throws Exception {
        var parts = new String[] {"{\\\"pa", "th\\\":", "\\\"a/b", ".txt\\\"}"};
        var chunks = new ArrayList<String>();
        chunks.add("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"read\",\"arguments\":\"\"}}]}}]}");
        for (var p : parts) {
            chunks.add("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\""
                    + p + "\"}}]}}]}");
        }
        chunks.add("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
        chunks.add(USAGE);
        chunks.add("[DONE]");
        handler = (n, ex) -> sse(ex, chunks.toArray(String[]::new));
        var events = run(simple());
        assertEquals(new LlmEvent.ToolUseStart("call_1", "read"), events.get(0));
        var input = new StringBuilder();
        for (var e : events) {
            if (e instanceof LlmEvent.ToolUseInput(var id, var fragment)) {
                assertEquals("call_1", id);
                input.append(fragment);
            }
        }
        assertEquals("{\"path\":\"a/b.txt\"}", input.toString());
        assertEquals(new LlmEvent.MessageStop("tool_calls"), events.getLast());
        assertEquals(7, events.size());
        assertEquals(new LlmEvent.Usage(12, 7), events.get(5));
    }

    private static String call(int index, String id, String name, String args) {
        var head = id == null ? "" : "\"id\":\"" + id + "\",\"type\":\"function\",";
        var fn = (name == null ? "" : "\"name\":\"" + name + "\",") + "\"arguments\":\"" + args + "\"";
        return "{\"index\":" + index + "," + head + "\"function\":{" + fn + "}}";
    }

    @Test
    void assemblesParallelToolCalls() throws Exception {
        handler = (n, ex) -> sse(ex,
                "{\"choices\":[{\"delta\":{\"tool_calls\":[" + call(0, "a", "grep", "{\\\"q") + "]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[" + call(1, "b", "read", "{\\\"p") + "]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[" + call(0, null, null, "\\\":1}") + ","
                        + call(1, null, null, "\\\":2}") + "]}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", "[DONE]");
        var events = run(simple());
        var inputs = new java.util.TreeMap<String, StringBuilder>();
        var starts = new ArrayList<String>();
        for (var e : events) {
            switch (e) {
                case LlmEvent.ToolUseStart s -> starts.add(s.id() + ":" + s.tool());
                case LlmEvent.ToolUseInput i -> inputs.computeIfAbsent(i.id(), k -> new StringBuilder())
                        .append(i.jsonFragment());
                default -> { }
            }
        }
        assertEquals(List.of("a:grep", "b:read"), starts);
        assertEquals("{\"q\":1}", inputs.get("a").toString());
        assertEquals("{\"p\":2}", inputs.get("b").toString());
        assertEquals(new LlmEvent.MessageStop("tool_calls"), events.getLast());
    }

    @Test
    void mapsMessagesAndToolsToWireFormat() throws Exception {
        handler = (n, ex) -> sse(ex, textChunk("ok"), STOP, "[DONE]");
        var input = json.readTree("{\"path\":\"x\"}");
        var schema = json.readTree("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}");
        var messages = List.of(
                Message.user("read x"),
                new Message(Role.ASSISTANT, List.of(new Text("sure"), new ToolUse("c1", "read", input))),
                new Message(Role.USER, List.of(new ToolResult("c1", "contents", false))));
        run(new LlmRequest("sys", messages, List.of(new ToolDefinition("read", "Reads", schema)), 50));
        var body = json.readTree(bodies.get(0));
        var m = body.get("messages");
        assertEquals(4, m.size());
        assertEquals("assistant", m.get(2).get("role").asString());
        assertEquals("sure", m.get(2).get("content").asString());
        var tc = m.get(2).get("tool_calls").get(0);
        assertEquals("c1", tc.get("id").asString());
        assertEquals("function", tc.get("type").asString());
        assertEquals("read", tc.get("function").get("name").asString());
        assertEquals("{\"path\":\"x\"}", tc.get("function").get("arguments").asString());
        assertEquals("tool", m.get(3).get("role").asString());
        assertEquals("c1", m.get(3).get("tool_call_id").asString());
        assertEquals("contents", m.get(3).get("content").asString());
        var tool = body.get("tools").get(0);
        assertEquals("function", tool.get("type").asString());
        assertEquals("read", tool.get("function").get("name").asString());
        assertEquals("object", tool.get("function").get("parameters").get("type").asString());
        assertEquals(50, body.get("max_tokens").asInt());
    }

    @Test
    void retriesAfter429UsingRetryAfter() throws Exception {
        handler = (n, ex) -> {
            if (n == 1) status(ex, 429, "{\"error\":\"slow down\"}", "Retry-After", "3");
            else sse(ex, textChunk("hi"), STOP, "[DONE]");
        };
        var events = run(simple());
        assertEquals(2, calls.get());
        assertEquals(List.of(Duration.ofSeconds(3)), sleeps);
        assertEquals(new LlmEvent.TextDelta("hi"), events.get(0));
    }

    @Test
    void retriesAfter500WithBackoff() throws Exception {
        handler = (n, ex) -> {
            if (n == 1) status(ex, 500, "boom");
            else sse(ex, textChunk("hi"), STOP, "[DONE]");
        };
        run(simple());
        assertEquals(2, calls.get());
        assertEquals(1, sleeps.size());
        var ms = sleeps.get(0).toMillis();
        assertTrue(ms >= 500 && ms <= 1000, "delay " + ms);
    }

    @Test
    void givesUpAfterFiveAttempts() {
        handler = (n, ex) -> status(ex, 503, "down");
        var e = assertThrows(ExecutionException.class, () -> run(simple()));
        assertEquals(503, failureOf(e).status());
        assertEquals(5, calls.get());
        assertEquals(4, sleeps.size());
        assertTrue(sleeps.stream().allMatch(d -> d.toSeconds() <= 30));
    }

    @Test
    void doesNotRetry400() {
        handler = (n, ex) -> status(ex, 400, "{\"error\":\"bad request\"}");
        var e = assertThrows(ExecutionException.class, () -> run(simple()));
        var failure = failureOf(e);
        assertEquals(400, failure.status());
        assertTrue(failure.getMessage().contains("bad request"));
        assertEquals(1, calls.get());
        assertTrue(sleeps.isEmpty());
        assertFalse(failure.getMessage().contains(KEY));
    }

    @Test
    void backoffGrowsAndIsCapped() {
        var c = client();
        assertTrue(c.backoff(1).toMillis() <= 1000);
        assertTrue(c.backoff(3).toMillis() >= 2000 && c.backoff(3).toMillis() <= 4000);
        assertTrue(c.backoff(10).toMillis() <= 30_000);
    }

    @Test
    void parsesRetryAfterHeader() {
        assertEquals(Duration.ofSeconds(7), OpenAiCompatibleClient.parseRetryAfter("7"));
        assertNull(OpenAiCompatibleClient.parseRetryAfter("soon"));
        assertNull(OpenAiCompatibleClient.parseRetryAfter(null));
        assertEquals(Duration.ZERO, OpenAiCompatibleClient.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT"));
    }

    @Test
    void cancelInMiddleOfStreamClosesWithoutMoreEvents() throws Exception {
        var release = new CountDownLatch(1);
        handler = (n, ex) -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (var out = ex.getResponseBody()) {
                out.write(("data: " + textChunk("one") + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                release.await(10, TimeUnit.SECONDS);
                out.write(("data: " + textChunk("two") + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.write(("data: " + STOP + "\n\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException | InterruptedException ignored) {
                // The client closed the connection.
            }
        };
        var token = new CancellationToken();
        var events = new CopyOnWriteArrayList<LlmEvent>();
        var done = new CompletableFuture<Void>();
        client().stream(simple(), token).subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(LlmEvent e) { events.add(e); token.cancel(); }
            public void onError(Throwable t) { done.completeExceptionally(t); }
            public void onComplete() { done.complete(null); }
        });
        done.get(10, TimeUnit.SECONDS);
        release.countDown();
        assertEquals(List.of(new LlmEvent.TextDelta("one")), events);
    }

    @Test
    void settingsToStringHidesKey() {
        var settings = OpenAiSettings.of("http://x", "m", KEY, Map.of());
        assertFalse(settings.toString().contains(KEY));
    }
}
