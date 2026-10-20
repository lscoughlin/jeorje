package org.github.lscoughlin.jeorje.tools.mcp;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A client for one MCP server. It does the handshake, lists tools, and calls tools. It matches
 * each response to its request by id. It answers server requests with "method not found".
 */
public final class McpClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The protocol version that the client asks for. */
    public static final String PROTOCOL_VERSION = "2025-06-18";

    /** A tool that the server offers. */
    public record RemoteTool(String name, String description, JsonNode inputSchema, JsonNode annotations) {}

    /** The result of a tool call. The content is the array of content blocks. */
    public record CallResult(boolean isError, JsonNode content) {}

    private final String name;
    private final McpTransport transport;
    private final Duration timeout;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean toolsStale = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Throwable failure;
    private volatile String serverName = "";

    public McpClient(String name, McpTransport transport, Duration timeout) {
        this.name = name;
        this.transport = transport;
        this.timeout = timeout;
        transport.onMessage(this::receive);
        transport.onClose(this::ended);
    }

    /** Opens the transport and runs the handshake. */
    public void initialize() throws IOException {
        transport.start();
        var params = JSON.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "jeorje").put("version", "0.1.0");
        var result = request("initialize", params, timeout);
        var version = result.path("protocolVersion").asString(PROTOCOL_VERSION);
        transport.protocolVersion(version);
        serverName = result.path("serverInfo").path("name").asString("");
        notify("notifications/initialized", null);
    }

    /** The name that the server gives itself. */
    public String serverName() {
        return serverName;
    }

    /** Lists all tools. It follows the cursor until the server has no more pages. */
    public List<RemoteTool> listTools() throws IOException {
        var tools = new ArrayList<RemoteTool>();
        String cursor = null;
        do {
            var params = JSON.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            var result = request("tools/list", params, timeout);
            for (var tool : result.path("tools")) {
                var toolName = tool.path("name").asString("");
                if (toolName.isEmpty()) continue;
                tools.add(new RemoteTool(toolName, tool.path("description").asString(""),
                        tool.has("inputSchema") ? tool.get("inputSchema") : JSON.createObjectNode().put("type", "object"),
                        tool.path("annotations")));
            }
            cursor = result.has("nextCursor") && !result.get("nextCursor").isNull()
                    ? result.get("nextCursor").asString(null) : null;
        } while (cursor != null);
        toolsStale.set(false);
        return tools;
    }

    /** Calls a tool. A JSON-RPC error and a timeout both throw {@link IOException}. */
    public CallResult callTool(String tool, JsonNode arguments, Duration callTimeout) throws IOException {
        var params = JSON.createObjectNode();
        params.put("name", tool);
        params.set("arguments", arguments == null || arguments.isNull() ? JSON.createObjectNode() : arguments);
        var result = request("tools/call", params, callTimeout);
        return new CallResult(result.path("isError").asBoolean(false), result.path("content"));
    }

    /** True after the server said that its tool list changed. */
    public boolean toolsStale() {
        return toolsStale.get();
    }

    // ------------------------------------------------------------ JSON-RPC

    private JsonNode request(String method, ObjectNode params, Duration wait) throws IOException {
        if (failure != null) throw new IOException("MCP server " + name + " is not connected: " + failure.getMessage());
        var id = ids.incrementAndGet();
        var future = new CompletableFuture<JsonNode>();
        pending.put(id, future);
        var message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        if (params != null) message.set("params", params);
        transport.send(message).whenComplete((ok, error) -> {
            if (error != null) future.completeExceptionally(error);
        });
        try {
            var response = future.get(wait.toMillis(), TimeUnit.MILLISECONDS);
            if (response.has("error")) {
                var error = response.get("error");
                throw new IOException("MCP error " + error.path("code").asInt() + ": "
                        + error.path("message").asString("unknown error"));
            }
            return response.path("result");
        } catch (TimeoutException e) {
            cancel(id, "timeout");
            throw new IOException("MCP server " + name + " did not answer " + method + " in " + wait.toSeconds() + " s");
        } catch (InterruptedException e) {
            cancel(id, "interrupted");
            Thread.currentThread().interrupt();
            throw new IOException("MCP call interrupted", e);
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("MCP server " + name + ": " + cause.getMessage(), cause);
        } finally {
            pending.remove(id);
        }
    }

    private void cancel(long id, String reason) {
        var params = JSON.createObjectNode();
        params.put("requestId", id);
        params.put("reason", reason);
        notify("notifications/cancelled", params);
    }

    private void notify(String method, ObjectNode params) {
        var message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        if (params != null) message.set("params", params);
        transport.send(message).exceptionally(error -> {
            log.atDebug().log(() -> "MCP " + name + " notification " + method + " failed: " + error.getMessage());
            return null;
        });
    }

    private void receive(JsonNode message) {
        var hasMethod = message.has("method");
        var hasId = message.has("id") && !message.get("id").isNull();
        if (hasMethod && hasId) {
            // A request from the server. The client offers no server-side features.
            var reply = JSON.createObjectNode();
            reply.put("jsonrpc", "2.0");
            reply.set("id", message.get("id"));
            var error = reply.putObject("error");
            error.put("code", -32601);
            error.put("message", "Method not found");
            transport.send(reply);
        } else if (hasMethod) {
            if (message.get("method").asString("").equals("notifications/tools/list_changed")) {
                toolsStale.set(true);
            }
        } else if (hasId) {
            var future = pending.get(message.get("id").asLong(-1));
            if (future != null) future.complete(message);
        }
    }

    private void ended(Throwable error) {
        failure = error == null ? new IOException("connection ended") : error;
        pending.values().forEach(f -> f.completeExceptionally(failure));
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) transport.close();
    }
}
