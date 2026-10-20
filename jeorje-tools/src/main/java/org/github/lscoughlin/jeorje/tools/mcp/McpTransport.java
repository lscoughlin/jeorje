package org.github.lscoughlin.jeorje.tools.mcp;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/**
 * Moves JSON-RPC messages to and from one MCP server. The transport delivers each incoming
 * message to the message handler. It calls the close handler once, when the server connection ends.
 */
public interface McpTransport extends AutoCloseable {

    /** Sets the handler for messages from the server. Call before {@link #start()}. */
    void onMessage(Consumer<JsonNode> handler);

    /** Sets the handler for the end of the connection. Call before {@link #start()}. */
    void onClose(Consumer<Throwable> handler);

    /** Opens the connection. */
    void start() throws IOException;

    /** Sends one message. The future completes when the server has accepted the message. */
    CompletableFuture<Void> send(JsonNode message);

    /** Tells the transport which protocol version the server chose. */
    default void protocolVersion(String version) {
    }

    @Override
    void close();
}
