package org.github.lscoughlin.jeorje.swarm;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.github.lscoughlin.jeorje.swarm.Messages.AgentPayload;
import org.github.lscoughlin.jeorje.swarm.Messages.AskPayload;

/**
 * Sends agent messages and questions to a peer over the SSH channel and waits for the answers. A question
 * has a request id. The answer or the error of the peer has the same id. A question fails with the reason of
 * the error, or with a timeout, or when the peer is lost. A question never throws.
 */
final class SshMessenger {
    /** Sends one typed payload to a peer over SSH. The stage fails when the peer cannot be reached. */
    @FunctionalInterface
    interface Sender {
        CompletableFuture<Void> send(String peerId, String type, Object body);
    }

    private record Pending(String peerId, CompletableFuture<String> future) {}

    private final Sender sender;
    private final Duration defaultTimeout;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    SshMessenger(Sender sender, Duration defaultTimeout) {
        this.sender = sender;
        this.defaultTimeout = defaultTimeout;
    }

    /** Sends text to an agent on a peer. */
    CompletableFuture<Void> message(String peerId, String toAgent, String text) {
        return sender.send(peerId, Messages.AGENT_MESSAGE, new AgentPayload("text", toAgent, null, null, null, text));
    }

    /** Asks a question of an agent on a peer. */
    CompletableFuture<String> ask(String peerId, String toAgent, String question, Duration timeout) {
        var limit = timeout == null ? defaultTimeout : timeout;
        var requestId = UUID.randomUUID().toString();
        var future = new CompletableFuture<String>();
        pending.put(requestId, new Pending(peerId, future));
        future.whenComplete((v, e) -> pending.remove(requestId));
        CompletableFuture<Void> sent;
        try {
            sent = sender.send(peerId, Messages.AGENT_ASK, new AskPayload(requestId, toAgent, question));
        } catch (RuntimeException e) {
            sent = CompletableFuture.failedFuture(e);
        }
        sent.whenComplete((v, e) -> {
            if (e != null) {
                future.completeExceptionally(e instanceof java.util.concurrent.CompletionException && e.getCause() != null
                        ? e.getCause() : e);
            }
        });
        CompletableFuture.delayedExecutor(limit.toMillis(), TimeUnit.MILLISECONDS).execute(() ->
                future.completeExceptionally(new TimeoutException(
                        "the peer " + peerId + " did not answer in " + limit.toSeconds() + " s")));
        return future;
    }

    /** Completes a question with an answer. Returns false when the id is unknown or the peer is wrong. */
    boolean answered(String fromPeer, String requestId, String text) {
        var entry = requestId == null ? null : pending.get(requestId);
        if (entry == null || !entry.peerId().equals(fromPeer)) {
            return false;
        }
        pending.remove(requestId);
        return entry.future().complete(text == null ? "" : text);
    }

    /** Fails a question with the reason of the peer. Returns false when the id is unknown or the peer is wrong. */
    boolean failed(String fromPeer, String requestId, String reason) {
        var entry = requestId == null ? null : pending.get(requestId);
        if (entry == null || !entry.peerId().equals(fromPeer)) {
            return false;
        }
        pending.remove(requestId);
        return entry.future().completeExceptionally(new IllegalStateException(reason == null ? "failed" : reason));
    }

    /** Fails all questions to a peer that is gone. */
    void peerLost(String peerId, Throwable reason) {
        pending.values().stream().filter(p -> p.peerId().equals(peerId))
                .forEach(p -> p.future().completeExceptionally(reason));
    }

    /** Fails all open questions. */
    void close(Throwable reason) {
        pending.values().forEach(p -> p.future().completeExceptionally(reason));
        pending.clear();
    }
}
