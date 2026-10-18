package org.github.lscoughlin.jeorje.core.port;

import java.util.function.Consumer;

/** Port that sends and receives swarm messages. */
public interface Transport extends AutoCloseable {
    /** A message with its sender. The body is one JSON object. */
    record Envelope(String peerId, String body) {}

    /** Sends a message to a peer. It throws if the peer cannot be reached. */
    void send(String peerId, String body);

    /** Sets the handler for incoming messages. The handler runs on a transport thread. */
    void onMessage(Consumer<Envelope> handler);

    @Override
    void close();
}
