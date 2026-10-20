package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.github.lscoughlin.jeorje.core.port.Filters;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;
import org.github.lscoughlin.jeorje.core.port.Syntax;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.index.store.LuceneContextStore;
import org.github.lscoughlin.jeorje.swarm.TlsSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real {@code jeorje run} processes on loopback. Each process talks to its own stub chat server. One process
 * (the host) stays alive in a long tool call. The other process (the delegator) delegates a task to it.
 */
class SwarmProcessTest {
    private static final String MARKER = "DELEGATED-TASK-MARKER";
    private static final String RESULT = "walrusresult9317";
    private static final String ANSWER = "fortytwo7718";
    private static final String SUMMARY = """
        Outcome: completed
        Done: Finished the work and found %s.
        Files: none
        Branch: none
        Decisions: none
        Verification: none
        Open issues: none
        Keywords: walrus, delegated, work""".formatted(RESULT);
    private static final Pattern QUESTION_ID = Pattern.compile(", id (q\\d+)\\]");
    private static final String TOKEN = "process-test-token-5521";

    @TempDir Path dir;
    private final List<Process> processes = new ArrayList<>();
    private final List<StubChat> stubs = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        processes.forEach(Process::destroyForcibly);
        stubs.forEach(StubChat::close);
    }

    // ---- the stub chat server ----

    /** One scripted reply: text, or one tool call. */
    record Reply(String text, String toolId, String tool, String arguments, long delayMs) {
        static Reply text(String text) { return new Reply(text, null, null, null, 0); }

        static Reply tool(String id, String tool, String arguments) { return new Reply("", id, tool, arguments, 0); }

        Reply after(long ms) { return new Reply(text, toolId, tool, arguments, ms); }
    }

    /** An OpenAI-compatible {@code /chat/completions} server. A function picks the reply from the request body. */
    static final class StubChat {
        final HttpServer server;
        final List<String> requests = new CopyOnWriteArrayList<>();
        private final Function<String, Reply> script;

        StubChat(Function<String, Reply> script) throws IOException {
            this.script = script;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::serve);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
                var t = new Thread(r, "stub-chat");
                t.setDaemon(true);
                return t;
            }));
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }

        private void serve(HttpExchange exchange) throws IOException {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            Reply reply;
            try {
                reply = script.apply(body);
                if (reply.delayMs() > 0) Thread.sleep(reply.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                var sb = new StringBuilder();
                sb.append(chunk("{\"role\":\"assistant\",\"content\":\"\"}", "null"));
                if (!reply.text().isEmpty()) {
                    sb.append(chunk("{\"content\":" + quote(reply.text()) + "}", "null"));
                }
                if (reply.tool() != null) {
                    sb.append(chunk("{\"tool_calls\":[{\"index\":0,\"id\":" + quote(reply.toolId())
                            + ",\"type\":\"function\",\"function\":{\"name\":" + quote(reply.tool())
                            + ",\"arguments\":" + quote(reply.arguments()) + "}}]}", "null"));
                }
                sb.append("data: {\"id\":\"s\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"stub\","
                        + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\""
                        + (reply.tool() != null ? "tool_calls" : "stop") + "\"}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n\n");
                sb.append("data: [DONE]\n\n");
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // The client went away. That is fine.
            }
        }

        private static String chunk(String delta, String finish) {
            return "data: {\"id\":\"s\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"stub\","
                    + "\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":" + finish + "}]}\n\n";
        }

        private static String quote(String text) {
            return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(text);
        }

        void close() { server.stop(0); }
    }

    private StubChat stub(Function<String, Reply> script) throws IOException {
        var stub = new StubChat(script);
        stubs.add(stub);
        return stub;
    }

    // ---- the processes ----

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /** The options of one process. */
    record Options(String name, Path home, String bind, boolean discovery, String tls, List<String> peers,
                   Map<String, String> env) {}

    private Path project(String name) throws IOException {
        var project = dir.resolve(name);
        Files.createDirectories(project);
        return project;
    }

    private Process launch(Options options, StubChat chat, String task) throws IOException {
        var project = project(options.name());
        Files.createDirectories(options.home());
        var config = new StringBuilder("""
            swarm:
              enabled: true
              bind: "%s"
              gossipIntervalMs: 200
              discovery: %s
            """.formatted(options.bind(), options.discovery()));
        if (options.tls() != null) config.append(options.tls());
        config.append("index:\n  embeddings: false\n");
        var configFile = dir.resolve(options.name() + "-config.yaml");
        Files.writeString(configFile, config.toString());
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                "-Duser.home=" + options.home(),
                "org.github.lscoughlin.jeorje.cli.JeorjeMain",
                "--dir", project.toString(), "--config", configFile.toString(), "--base-url", chat.baseUrl(),
                "--yolo"));
        options.peers().forEach(peer -> {
            command.add("--peer");
            command.add(peer);
        });
        command.addAll(List.of("run", task));
        var builder = new ProcessBuilder(command).directory(project.toFile());
        builder.environment().put("OPENROUTER_API_KEY", "test-key-not-real");
        builder.environment().put("JEORJE_SWARM_TOKEN", TOKEN);
        builder.environment().put("HOME", options.home().toString());
        builder.environment().putAll(options.env());
        builder.redirectOutput(dir.resolve(options.name() + ".out").toFile());
        builder.redirectError(dir.resolve(options.name() + ".err").toFile());
        var process = builder.start();
        processes.add(process);
        return process;
    }

    private String out(String name) throws IOException { return Files.readString(dir.resolve(name + ".out")); }

    private String err(String name) throws IOException { return Files.readString(dir.resolve(name + ".err")); }

    private static void await(java.util.function.BooleanSupplier condition, String what) throws Exception {
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("not reached in 60 s: " + what);
            Thread.sleep(50);
        }
    }

    // ---- the scripts ----

    /** The host: its own agent sleeps in a long tool call. A delegated task gets the reply of {@code task}. */
    private static Function<String, Reply> hostScript(Function<String, Reply> task) {
        return body -> body.contains(MARKER) ? task.apply(body)
                : body.contains("call_hold") ? Reply.text("held") : Reply.tool("call_hold", "bash",
                        "{\"command\":\"sleep 120\",\"timeout\":200}");
    }

    private static final Function<String, Reply> FINISH_AT_ONCE = body -> Reply.text(SUMMARY);

    /** The delegator: it calls spawn_agent for the peer, tries again while the peer is not yet known. */
    private static Function<String, Reply> delegatorScript(String peer, String mode, Function<String, Reply> next) {
        var attempts = new AtomicInteger();
        return body -> {
            if (body.contains("call_spawn")) {
                if (body.contains("No live peer") && attempts.get() < 40) {
                    return spawn(peer, mode, attempts).after(500);
                }
                return next.apply(body);
            }
            return spawn(peer, mode, attempts).after(500);
        };
    }

    private static Reply spawn(String peer, String mode, AtomicInteger attempts) {
        var id = "call_spawn" + attempts.incrementAndGet();
        return Reply.tool(id, "spawn_agent", "{\"name\":\"w\",\"task\":\"" + MARKER + " do the work\",\"peer\":\""
                + peer + "\",\"mode\":\"" + mode + "\"}");
    }

    // ---- helpers for the checks ----

    private void assertSummaryChunkInHistory(Path project, String needle) throws Exception {
        Path index;
        try (var walk = Files.walk(project.resolve(".jeorje").resolve("sessions"))) {
            index = walk.filter(p -> p.getFileName().toString().equals("transcript.jsonl")).findFirst()
                    .orElseThrow().resolveSibling("index");
        }
        try (var store = LuceneContextStore.open(index, null, LuceneContextStore.Options.defaults())) {
            var hits = store.search(new HistoryQuery(needle, Syntax.TEXT, Filters.none(), 20));
            var kinds = hits.stream().map(h -> h.chunk().kind()).collect(Collectors.toList());
            assertTrue(kinds.contains(SubagentRuntime.SUMMARY_KIND), "chunk kinds with the result text: " + kinds);
        }
    }

    private void runDelegation(Options host, Options delegator) throws Exception {
        var hostChat = stub(hostScript(FINISH_AT_ONCE));
        var delegatorChat = stub(delegatorScript(host.name(), "wait", body -> Reply.text("relay done")));
        launch(host, hostChat, "hold the line");
        await(() -> hostChat.requests.size() >= 1, "the host agent started");
        var process = launch(delegator, delegatorChat, "delegate the work");
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the delegator did not end: " + err(delegator.name()));
        assertEquals(0, process.exitValue(), err(delegator.name()) + out(delegator.name()));
        assertTrue(out(delegator.name()).contains("relay done"), out(delegator.name()));
        // The last request of the delegator holds the tool result with the summary of the remote subagent.
        var last = delegatorChat.requests.getLast();
        assertTrue(last.contains(RESULT), last);
        assertTrue(last.contains("Remote subagent on the peer " + host.name()), last);
        // The host ran the task in its own process.
        assertTrue(hostChat.requests.stream().anyMatch(r -> r.contains(MARKER)));
        assertSummaryChunkInHistory(dir.resolve(delegator.name()), RESULT);
    }

    // ---- the tests ----

    @Test
    void aTaskIsDelegatedToAPeerProcessAndTheSummaryArrivesAsASubagentSummary() throws Exception {
        var port = freePort();
        var host = new Options("host-proj", dir.resolve("home-host"), "127.0.0.1:" + port, false, null, List.of(),
                Map.of());
        var delegator = new Options("deleg-proj", dir.resolve("home-deleg"), "127.0.0.1:0", false, null,
                List.of("127.0.0.1:" + port), Map.of());
        runDelegation(host, delegator);
    }

    @Test
    void theSameDelegationWorksOverTls() throws Exception {
        var store = dir.resolve("swarm.p12");
        var keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "jeorje", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "changeit", "-dname", "CN=localhost", "-validity", "3",
                "-ext", "san=ip:127.0.0.1,dns:localhost").redirectErrorStream(true).start();
        var keytoolOut = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(keytool.waitFor(60, TimeUnit.SECONDS) && keytool.exitValue() == 0, keytoolOut);
        var tlsYaml = "  tls:\n    keyStore: \"" + store + "\"\n    passwordEnv: SWARM_TEST_STORE_PASSWORD\n";
        var port = freePort();
        var env = Map.of("SWARM_TEST_STORE_PASSWORD", "changeit");
        var host = new Options("host-tls", dir.resolve("home-host"), "127.0.0.1:" + port, false, tlsYaml, List.of(),
                env);
        var delegator = new Options("deleg-tls", dir.resolve("home-deleg"), "127.0.0.1:0", false, tlsYaml,
                List.of("127.0.0.1:" + port), env);
        runDelegation(host, delegator);
        // The port of the host speaks TLS: a client with the shared key store completes the handshake.
        var context = TlsSupport.fromKeyStore(store, "changeit".toCharArray());
        try (var socket = (javax.net.ssl.SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.startHandshake();
            assertTrue(socket.getSession().isValid());
        }
    }

    @Test
    void aQuestionGoesFromTheDelegatorToTheRemoteSubagentAndTheAnswerComesBack() throws Exception {
        var port = freePort();
        var host = new Options("host-ask", dir.resolve("home-host"), "127.0.0.1:" + port, false, null, List.of(),
                Map.of());
        var delegator = new Options("deleg-ask", dir.resolve("home-deleg"), "127.0.0.1:0", false, null,
                List.of("127.0.0.1:" + port), Map.of());
        var sleeps = new AtomicInteger();
        Function<String, Reply> task = body -> {
            if (body.contains("call_answer")) return Reply.text(SUMMARY);
            var matcher = QUESTION_ID.matcher(body);
            if (matcher.find()) {
                return Reply.tool("call_answer", "agent_answer",
                        "{\"questionId\":\"" + matcher.group(1) + "\",\"text\":\"" + ANSWER + "\"}");
            }
            return Reply.tool("call_nap" + sleeps.incrementAndGet(), "bash", "{\"command\":\"sleep 1\"}");
        };
        var hostChat = stub(hostScript(task));
        var delegatorChat = stub(delegatorScript(host.name(), "background", body -> body.contains("call_ask")
                ? Reply.text("asked and done")
                : Reply.tool("call_ask", "agent_ask", "{\"to\":\"w\",\"question\":\"what is 6 x 7\"}")));
        launch(host, hostChat, "hold the line");
        await(() -> hostChat.requests.size() >= 1, "the host agent started");
        var process = launch(delegator, delegatorChat, "delegate and ask");
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the delegator did not end: " + err(delegator.name()));
        assertEquals(0, process.exitValue(), err(delegator.name()) + out(delegator.name()));
        var last = delegatorChat.requests.getLast();
        assertTrue(last.contains("call_ask") && last.contains(ANSWER), last);
        assertTrue(hostChat.requests.stream().anyMatch(r -> r.contains("what is 6 x 7")),
                "the question reached the remote subagent");
    }
}
