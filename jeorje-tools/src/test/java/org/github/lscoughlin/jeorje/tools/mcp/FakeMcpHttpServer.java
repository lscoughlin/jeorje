package org.github.lscoughlin.jeorje.tools.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * A fake MCP server for Streamable HTTP. It answers {@code tools/call} with an event stream and
 * all other requests with a JSON body. It records the request headers.
 */
public final class FakeMcpHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final List<String> sessionHeaders = new CopyOnWriteArrayList<>();
    private final List<String> authorization = new CopyOnWriteArrayList<>();

    public FakeMcpHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    /** The Mcp-Session-Id header of each request; "-" when absent. */
    public List<String> sessionHeaders() {
        return sessionHeaders;
    }

    public List<String> authorization() {
        return authorization;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            sessionHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")).replace("null", "-"));
            authorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            if (exchange.getRequestMethod().equals("DELETE")) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            var request = FakeMcpLogic.JSON.readTree(exchange.getRequestBody().readAllBytes());
            var response = FakeMcpLogic.handle(request);
            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            if (response == null) {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.sendResponseHeaders(504, -1);
                return;
            }
            var json = FakeMcpLogic.JSON.writeValueAsString(response);
            exchange.getResponseHeaders().add("Mcp-Session-Id", "session-1");
            var sse = request.path("method").asString("").equals("tools/call");
            exchange.getResponseHeaders().add("Content-Type", sse ? "text/event-stream" : "application/json");
            var body = (sse ? "event: message\ndata: " + json + "\n\n" : json).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
