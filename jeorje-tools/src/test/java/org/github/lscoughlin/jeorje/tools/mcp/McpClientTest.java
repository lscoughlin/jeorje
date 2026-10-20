package org.github.lscoughlin.jeorje.tools.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.permissions.NetworkPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class McpClientTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FakeMcpHttpServer http;
    private McpClient client;
    private StdioTransport stdio;

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        if (http != null) http.close();
    }

    private McpClient connect(String kind, Duration timeout) throws IOException {
        if (kind.equals("stdio")) {
            var java = ProcessHandle.current().info().command().orElseThrow();
            stdio = new StdioTransport("fake", List.of(java, "-cp", System.getProperty("java.class.path"),
                    FakeMcpServer.class.getName()), Path.of("."), StdioTransport.environmentFor(System.getenv(), List.of(), Map.of()));
            client = new McpClient("fake", stdio, timeout);
        } else {
            http = new FakeMcpHttpServer();
            var open = new NetworkPolicy(List.of(), List.of(), host -> List.of(java.net.InetAddress.getByName("93.184.216.34")));
            client = new McpClient("fake", new HttpTransport("fake", http.url(), Map.of("Authorization", "Bearer ${TOKEN}"),
                    open, Map.of("TOKEN", "abc")::get, timeout), timeout);
        }
        client.initialize();
        return client;
    }

    @ParameterizedTest
    @ValueSource(strings = {"stdio", "http"})
    void handshakeAndPagedToolList(String kind) throws IOException {
        var c = connect(kind, Duration.ofSeconds(20));
        assertEquals("fake", c.serverName());
        var tools = c.listTools();
        assertEquals(List.of("echo", "fail", "hang"), tools.stream().map(McpClient.RemoteTool::name).toList());
        assertEquals("object", tools.getFirst().inputSchema().path("type").asString());
        assertFalse(c.toolsStale());
    }

    @ParameterizedTest
    @ValueSource(strings = {"stdio", "http"})
    void callReturnsContentAndErrorFlag(String kind) throws IOException {
        var c = connect(kind, Duration.ofSeconds(20));
        var ok = c.callTool("echo", JSON.createObjectNode().put("text", "hi"), Duration.ofSeconds(20));
        assertFalse(ok.isError());
        assertEquals("echo: hi", ok.content().get(0).path("text").asString());
        var failed = c.callTool("fail", null, Duration.ofSeconds(20));
        assertTrue(failed.isError());
        assertEquals("boom", failed.content().get(0).path("text").asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"stdio", "http"})
    void jsonRpcErrorBecomesIoException(String kind) throws IOException {
        var c = connect(kind, Duration.ofSeconds(20));
        var e = assertThrows(IOException.class, () -> c.callTool("nope", null, Duration.ofSeconds(20)));
        assertTrue(e.getMessage().contains("-32602"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"stdio", "http"})
    void hungServerHitsTheTimeout(String kind) throws IOException {
        var c = connect(kind, Duration.ofSeconds(20));
        var e = assertThrows(IOException.class, () -> c.callTool("hang", null, Duration.ofMillis(300)));
        assertTrue(e.getMessage().contains("did not answer"), e.getMessage());
    }

    @Test
    void stdioCloseLeavesNoChildProcess() throws IOException {
        connect("stdio", Duration.ofSeconds(20));
        var children = ProcessHandle.current().children().filter(h -> h.info().commandLine()
                .map(l -> l.contains(FakeMcpServer.class.getName())).orElse(false)).toList();
        assertEquals(1, children.size());
        client.close();
        for (var child : children) {
            assertTrue(child.onExit().orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).join() != null);
            assertFalse(child.isAlive());
        }
    }

    @Test
    void stdioServerExitFailsLaterCalls() throws IOException {
        var c = connect("stdio", Duration.ofSeconds(20));
        c.close();
        assertThrows(IOException.class, () -> c.listTools());
    }

    @Test
    void httpKeepsTheSessionAndExpandsHeaders() throws IOException {
        var c = connect("http", Duration.ofSeconds(20));
        c.listTools();
        assertEquals("-", http.sessionHeaders().getFirst());
        assertTrue(http.sessionHeaders().stream().skip(1).allMatch("session-1"::equals), http.sessionHeaders().toString());
        assertTrue(http.authorization().stream().allMatch("Bearer abc"::equals));
    }

    @Test
    void httpDoesNotConnectWhenThePolicyDenies() throws IOException {
        http = new FakeMcpHttpServer();
        var blocked = new NetworkPolicy(List.of(), List.of("127.0.0.1"));
        var transport = new HttpTransport("fake", http.url(), Map.of(), blocked, k -> null, Duration.ofSeconds(5));
        client = new McpClient("fake", transport, Duration.ofSeconds(5));
        assertThrows(IOException.class, client::initialize);
        assertTrue(http.sessionHeaders().isEmpty());
    }

    @Test
    void httpHeaderWithMissingVariableFails() {
        assertThrows(IllegalArgumentException.class, () -> HttpTransport.expand(Map.of("X", "${NOPE}"), k -> null));
    }

    @Test
    void stdioChildEnvironmentHasOnlyNamedVariables() {
        var parent = Map.of("PATH", "/bin", "OPENROUTER_API_KEY", "k", "GITHUB_TOKEN", "g", "FOO", "bar");
        var env = StdioTransport.environmentFor(parent, List.of("GITHUB_TOKEN", "MISSING"), Map.of("LIT", "1"));
        assertEquals(Map.of("PATH", "/bin", "GITHUB_TOKEN", "g", "LIT", "1"), env);
    }
}
