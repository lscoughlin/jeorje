package org.github.lscoughlin.jeorje.cli.interactive;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.swarm.RemoteAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An agent of another instance that the local user has in the foreground. After {@link #attach} the object
 * holds the history of the remote agent and collects its live lines. Typed text steers the remote agent. An
 * answer goes to a question of the remote agent. After {@link #close} no line is appended.
 */
final class RemoteAgent implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RemoteAgent.class);

    /** Opens an attachment. {@code SwarmNode::attach} fits. */
    @FunctionalInterface
    interface Attacher {
        RemoteAttachment attach(String peerId, String agentId, Consumer<String> onLine, Runnable onClosed);
    }

    /** The id of the agent in the interface: {@code peer:<peerId>/<agentId>}. */
    static String idOf(String peerId, String agentId) {
        return AgentRoster.PEER_PREFIX + peerId + "/" + agentId;
    }

    /** The peer id of an interface id made by {@link #idOf}, or null for another id. */
    static String peerOf(String id) {
        if (!id.startsWith(AgentRoster.PEER_PREFIX) || !id.contains("/")) return null;
        return id.substring(AgentRoster.PEER_PREFIX.length(), id.indexOf('/'));
    }

    /** The agent id of an interface id made by {@link #idOf}, or null for another id. */
    static String agentOf(String id) {
        if (!id.startsWith(AgentRoster.PEER_PREFIX) || !id.contains("/")) return null;
        return id.substring(id.indexOf('/') + 1);
    }

    private final Attacher attacher;
    private final long timeoutSeconds;
    private final List<String> history = new CopyOnWriteArrayList<>();
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private volatile Consumer<String> listener = line -> { };
    private volatile Runnable onClosed = () -> { };
    private volatile RemoteAttachment attachment;
    private volatile boolean closed;
    private volatile String peerId = "";
    private volatile String agentId = "";

    RemoteAgent(Attacher attacher) {
        this(attacher, 30);
    }

    RemoteAgent(Attacher attacher, long timeoutSeconds) {
        this.attacher = attacher;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** Receives each live line after it is appended. Set it before {@link #attach}. */
    RemoteAgent onLine(Consumer<String> value) {
        this.listener = value;
        return this;
    }

    /** Is called once when the host ends the attachment. */
    RemoteAgent onClosed(Runnable value) {
        this.onClosed = value;
        return this;
    }

    /**
     * Attaches and waits for the history.
     *
     * @throws IllegalStateException with the reason when the host refuses or does not answer
     */
    RemoteAgent attach(String peer, String agent) {
        this.peerId = peer;
        this.agentId = agent;
        var opened = attacher.attach(peer, agent, this::append, this::hostClosed);
        this.attachment = opened;
        try {
            history.addAll(opened.opened().get(timeoutSeconds, TimeUnit.SECONDS));
        } catch (java.util.concurrent.ExecutionException e) {
            opened.close();
            var cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(String.valueOf(cause.getMessage()), cause);
        } catch (java.util.concurrent.TimeoutException e) {
            opened.close();
            throw new IllegalStateException("the peer did not answer the attach in time", e);
        } catch (InterruptedException e) {
            opened.close();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        return this;
    }

    private void append(String line) {
        if (closed) return;
        lines.add(line);
        try {
            listener.accept(line);
        } catch (RuntimeException e) {
            log.atDebug().log(() -> "a line listener failed: " + e);
        }
    }

    private void hostClosed() {
        if (closed) return;
        closed = true;
        onClosed.run();
    }

    String peerId() { return peerId; }

    String agentId() { return agentId; }

    /** The history lines of the remote agent when the attach opened. */
    List<String> history() { return List.copyOf(history); }

    /** The live lines since the attach. */
    List<String> lines() { return List.copyOf(lines); }

    boolean closed() { return closed; }

    /** Sends steering text to the remote agent. */
    void steer(String text) {
        var current = attachment;
        if (current != null && !closed) current.steer(text);
    }

    /** Answers a question of the remote agent. The call fails with the reason when the host refuses. */
    void answer(String requestId, String text) {
        var current = attachment;
        if (current == null || closed) throw new IllegalStateException("the remote agent is not attached");
        try {
            current.answer(requestId, text).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            var cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(String.valueOf(cause.getMessage()), cause);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("the peer did not take the answer in time", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        var current = attachment;
        if (current != null) current.close();
    }
}
