package org.github.lscoughlin.jeorje.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.permissions.NetworkPolicy;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WebFetchToolTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    HttpServer server;
    String base;
    ToolContext context;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        context = new ToolContext(Path.of("."), new AgentId("a"), CancellationToken.none(), null, Clock.systemUTC());
        serve("/text", "text/plain", "hello");
        serve("/html", "text/html; charset=utf-8", "<html><head><title>x</title></head><body><h1>Title</h1>"
                + "<p>Some <a href=\"/l\">link</a> &amp; text.</p></body></html>");
        serve("/json", "application/json", "{\"a\":1}");
        serve("/bin", "application/octet-stream", "xx");
        redirect("/r1", "/text");
        redirect("/loop", "/loop");
        redirect("/priv", "http://10.0.0.1/secret");
        redirect("/file", "file:///etc/passwd");
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    void serve(String path, String type, String body) {
        server.createContext(path, ex -> send(ex, 200, type, body.getBytes(StandardCharsets.UTF_8)));
    }

    void redirect(String path, String to) {
        server.createContext(path, ex -> {
            ex.getResponseHeaders().add("Location", to);
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
    }

    static void send(HttpExchange ex, int status, String type, byte[] body) throws java.io.IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) ex.getResponseBody().write(body);
        ex.close();
    }

    WebFetchTool tool(boolean allowPrivate, List<String> allow, List<String> deny, long max) {
        return new WebFetchTool(new NetworkPolicy(allow, deny), allowPrivate, max, Duration.ofSeconds(10));
    }

    ToolResultData fetch(WebFetchTool tool, String url, boolean unused) throws Exception {
        return tool.execute(JSON.readTree("{\"url\":\"" + url + "\"}"), context);
    }

    WebFetchTool open() {
        return tool(true, List.of(), List.of(), WebFetchTool.DEFAULT_MAX_BYTES);
    }

    @Test
    void fetchesText() throws Exception {
        var r = fetch(open(), base + "/text", true);
        assertFalse(r.error(), r.output());
        assertEquals("hello", r.output());
    }

    @Test
    void followsRedirect() throws Exception {
        assertEquals("hello", fetch(open(), base + "/r1", true).output());
    }

    @Test
    void stopsAfterFiveRedirects() throws Exception {
        var r = fetch(open(), base + "/loop", true);
        assertTrue(r.error());
        assertTrue(r.output().contains("Too many redirects"), r.output());
    }

    @Test
    void refusesRedirectToPrivateAddress() throws Exception {
        var onlyLoopback = new WebFetchTool(new NetworkPolicy(List.of(), List.of()),
                host -> host.equals("127.0.0.1"), WebFetchTool.DEFAULT_MAX_BYTES, Duration.ofSeconds(5));
        assertEquals("hello", fetch(onlyLoopback, base + "/r1", true).output());
        var r = fetch(onlyLoopback, base + "/priv", true);
        assertTrue(r.error());
        assertTrue(r.output().contains("Refused"), r.output());
        assertTrue(r.output().contains("blocked") || r.output().contains("resolved"), r.output());
    }

    @Test
    void refusesLoopbackUnlessAllowed() throws Exception {
        var strict = tool(false, List.of(), List.of(), WebFetchTool.DEFAULT_MAX_BYTES);
        var r = fetch(strict, base + "/text", true);
        assertTrue(r.error());
        assertTrue(r.output().contains("blocked"), r.output());
        assertFalse(fetch(open(), base + "/text", true).error());
    }

    @Test
    void refusesOtherSchemes() throws Exception {
        assertTrue(fetch(open(), "ftp://example.com/x", true).error());
        var r = fetch(open(), base + "/file", true);
        assertTrue(r.error());
        assertTrue(r.output().contains("scheme"), r.output());
    }

    @Test
    void enforcesSizeCap() throws Exception {
        serve("/big", "text/plain", "x".repeat(100));
        var small = tool(true, List.of(), List.of(), 50);
        var r = fetch(small, base + "/big", true);
        assertTrue(r.error());
        assertTrue(r.output().contains("too large"), r.output());
        server.createContext("/chunked", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().write("y".repeat(100).getBytes(StandardCharsets.UTF_8));
            ex.close();
        });
        assertTrue(fetch(small, base + "/chunked", true).output().contains("too large"));
        assertFalse(fetch(tool(true, List.of(), List.of(), 100), base + "/big", true).error());
    }

    @Test
    void handlesContentTypes() throws Exception {
        var html = fetch(open(), base + "/html", true);
        assertEquals("# Title\n\nSome [link](/l) & text.", html.output());
        assertEquals("{\"a\":1}", fetch(open(), base + "/json", true).output());
        var bin = fetch(open(), base + "/bin", true);
        assertTrue(bin.error());
        assertTrue(bin.output().contains("Unsupported content type"));
    }

    @Test
    void reportsHttpErrors() throws Exception {
        server.createContext("/gone", ex -> send(ex, 404, "text/plain", "no".getBytes(StandardCharsets.UTF_8)));
        var r = fetch(open(), base + "/gone", true);
        assertTrue(r.error());
        assertTrue(r.output().contains("404"));
    }

    @Test
    void denyListBlocksAndAllowListPasses() throws Exception {
        var denied = tool(true, List.of(), List.of("127.0.0.1"), WebFetchTool.DEFAULT_MAX_BYTES);
        var r = fetch(denied, base + "/text", true);
        assertTrue(r.error());
        assertTrue(r.output().contains("deny list"), r.output());
        var allowed = tool(true, List.of("127.0.0.1"), List.of(), WebFetchTool.DEFAULT_MAX_BYTES);
        assertEquals("hello", fetch(allowed, base + "/text", true).output());
    }

    @Test
    void denyListAppliesToRedirectTarget() throws Exception {
        redirect("/to-denied", "http://blocked.example.test/x");
        var policy = new NetworkPolicy(List.of(), List.of("blocked.example.test"),
                host -> List.of(java.net.InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34})));
        var tool = new WebFetchTool(policy, true, WebFetchTool.DEFAULT_MAX_BYTES, Duration.ofSeconds(5));
        // The first host is the fake-resolved public address, so the request itself would not reach the server.
        var r = fetch(tool, "http://blocked.example.test/x", true);
        assertTrue(r.error());
        assertTrue(r.output().contains("deny list"), r.output());
    }
}
