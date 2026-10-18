package org.github.lscoughlin.jeorje.testkit.swarm;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.core.port.Transport;

/**
 * A {@link Transport} that connects many peers in one JVM. Make instances with
 * {@link InMemoryNetwork#join(String, String, String)}. The send target is the address of another transport.
 * A send to a missing or closed transport, or to a transport with another token, throws. A dropped or cut
 * message does not throw, as in a real network timeout.
 */
public final class InMemoryTransport implements Transport {
    static final int MAX_MESSAGE_CHARS = 1024 * 1024;

    private final InMemoryNetwork network;
    private final String address;
    private final String peerId;
    private final String token;
    private final ExecutorService inbox = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "in-memory-transport");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Consumer<Envelope> handler;
    private volatile boolean closed;

    InMemoryTransport(InMemoryNetwork network, String address, String peerId, String token) {
        this.network = network;
        this.address = address;
        this.peerId = peerId;
        this.token = token;
    }

    public String address() {
        return address;
    }

    public String peerId() {
        return peerId;
    }

    String token() {
        return token;
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public void send(String target, String body) {
        if (closed) {
            throw new IllegalStateException("transport is closed");
        }
        network.deliver(this, target, body);
    }

    @Override
    public void onMessage(Consumer<Envelope> handler) {
        this.handler = handler;
    }

    void enqueue(String senderPeerId, String body) {
        try {
            inbox.execute(() -> {
                var current = handler;
                if (current != null && !closed) {
                    try {
                        current.accept(new Envelope(senderPeerId, body));
                    } catch (RuntimeException e) {
                        // A failing handler must not stop the inbox.
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // Closed.
        }
    }

    @Override
    public void close() {
        closed = true;
        network.remove(address);
        inbox.shutdownNow();
    }
}
