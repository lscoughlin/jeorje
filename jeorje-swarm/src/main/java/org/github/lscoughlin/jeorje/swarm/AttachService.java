package org.github.lscoughlin.jeorje.swarm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.github.lscoughlin.jeorje.swarm.Messages.AnswerReplyBody;
import org.github.lscoughlin.jeorje.swarm.Messages.AnswerReqBody;
import org.github.lscoughlin.jeorje.swarm.Messages.AttachBody;
import org.github.lscoughlin.jeorje.swarm.Messages.LineBody;
import org.github.lscoughlin.jeorje.swarm.Messages.RosterBody;
import org.github.lscoughlin.jeorje.swarm.Messages.SteerBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Both sides of the foreground attach. On the host side it keeps the attachments that remote clients opened,
 * streams the live lines, and passes steering and answers to the local agent. On the client side it opens
 * attachments, asks for rosters, and delivers the frames to the caller. The frames travel over SSH only. All
 * methods that handle a frame run on the callback thread of the {@link SwarmNode}.
 */
final class AttachService {
    private static final Logger log = LoggerFactory.getLogger(AttachService.class);

    private record Hosted(String peerId, SwarmHandlers.ForegroundHost host) {}

    private record PendingRoster(String peerId, CompletableFuture<List<SwarmHandlers.PeerAgent>> future) {}

    private record PendingAnswer(String peerId, CompletableFuture<Void> future) {}

    private final SshMessenger.Sender sender;
    private final Supplier<SwarmHandlers> handlers;
    private final Duration timeout;
    private final Map<String, Hosted> hosted = new ConcurrentHashMap<>();
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final Map<String, PendingRoster> rosters = new ConcurrentHashMap<>();
    private final Map<String, PendingAnswer> answers = new ConcurrentHashMap<>();

    AttachService(SshMessenger.Sender sender, Supplier<SwarmHandlers> handlers, Duration timeout) {
        this.sender = sender;
        this.handlers = handlers;
        this.timeout = timeout;
    }

    private static String key(String peerId, String attachId) {
        return peerId + "/" + attachId;
    }

    private void send(String peerId, String type, Object body) {
        try {
            sender.send(peerId, type, body).exceptionally(e -> {
                log.atDebug().log(() -> "attach frame to " + peerId + " failed: " + e.getMessage());
                return null;
            });
        } catch (RuntimeException e) {
            log.atDebug().log(() -> "attach frame to " + peerId + " failed: " + e.getMessage());
        }
    }

    // ---- host side ------------------------------------------------------------------------------------

    void onAttach(String from, AttachBody body) {
        switch (body.kind() == null ? "" : body.kind()) {
            case "open" -> open(from, body);
            case "close" -> {
                var removed = hosted.remove(key(from, body.attachId()));
                if (removed != null) {
                    detachQuietly(removed.host());
                }
            }
            case "opened", "error", "closed" -> {
                var client = clients.get(body.attachId());
                if (client != null && client.peerId.equals(from)) {
                    client.onHostFrame(body);
                }
            }
            default -> log.atDebug().log(() -> "unknown attach kind ignored: " + body.kind());
        }
    }

    private void open(String from, AttachBody body) {
        var attachId = body.attachId();
        SwarmHandlers.ForegroundHost host;
        try {
            host = handlers.get().attach(from, body.agentId());
        } catch (RuntimeException e) {
            host = null;
            log.atWarn().log(() -> "attach failed: " + e);
        }
        if (host == null) {
            send(from, Messages.AGENT_ATTACH,
                    new AttachBody(attachId, "error", body.agentId(), List.of(), "unknown agent: " + body.agentId()));
            return;
        }
        var entry = new Hosted(from, host);
        var previous = hosted.put(key(from, attachId), entry);
        if (previous != null) {
            detachQuietly(previous.host());
        }
        // Lines that come while the history is read wait here, and go out after the history.
        var buffer = new ArrayList<String>();
        var live = new boolean[1];
        host.onLine(line -> {
            synchronized (buffer) {
                if (!live[0]) {
                    buffer.add(line);
                    return;
                }
            }
            send(from, Messages.AGENT_LINE, new LineBody(attachId, line));
        });
        List<String> history;
        try {
            history = host.history();
        } catch (RuntimeException e) {
            hosted.remove(key(from, attachId));
            detachQuietly(host);
            send(from, Messages.AGENT_ATTACH, new AttachBody(attachId, "error", body.agentId(), List.of(),
                    "cannot read the history: " + e.getMessage()));
            return;
        }
        send(from, Messages.AGENT_ATTACH, new AttachBody(attachId, "opened", host.agentId(), history, null));
        synchronized (buffer) {
            for (var line : buffer) {
                send(from, Messages.AGENT_LINE, new LineBody(attachId, line));
            }
            buffer.clear();
            live[0] = true;
        }
    }

    void onSteer(String from, SteerBody body) {
        var entry = hosted.get(key(from, body.attachId()));
        if (entry == null) {
            return;
        }
        try {
            entry.host().steer(body.text());
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "steer failed: " + e);
        }
    }

    void onAnswerRequest(String from, AnswerReqBody body) {
        var entry = hosted.get(key(from, body.attachId()));
        if (entry == null) {
            send(from, Messages.AGENT_ANSWER_REPLY,
                    new AnswerReplyBody(body.attachId(), body.requestId(), false, "not attached"));
            return;
        }
        try {
            entry.host().answer(body.requestId(), body.text());
            send(from, Messages.AGENT_ANSWER_REPLY, new AnswerReplyBody(body.attachId(), body.requestId(), true, null));
        } catch (RuntimeException e) {
            send(from, Messages.AGENT_ANSWER_REPLY, new AnswerReplyBody(body.attachId(), body.requestId(), false,
                    String.valueOf(e.getMessage())));
        }
    }

    void onRoster(String from, RosterBody body) {
        if ("reply".equals(body.kind())) {
            var pending = rosters.get(body.requestId());
            if (pending != null && pending.peerId().equals(from)) {
                rosters.remove(body.requestId());
                pending.future().complete(body.agents());
            }
            return;
        }
        List<SwarmHandlers.PeerAgent> agents;
        try {
            agents = handlers.get().roster();
        } catch (RuntimeException e) {
            agents = List.of();
        }
        send(from, Messages.AGENT_ROSTER, new RosterBody(body.requestId(), "reply", agents));
    }

    private static void detachQuietly(SwarmHandlers.ForegroundHost host) {
        try {
            host.detach();
        } catch (RuntimeException e) {
            log.atDebug().log(() -> "detach failed: " + e);
        }
    }

    // ---- client side ----------------------------------------------------------------------------------

    void onLine(String from, LineBody body) {
        var client = clients.get(body.attachId());
        if (client != null && client.peerId.equals(from)) {
            client.onLine(body.text());
        }
    }

    void onAnswerReply(String from, AnswerReplyBody body) {
        var pending = answers.get(body.attachId() + "/" + body.requestId());
        if (pending == null || !pending.peerId().equals(from)) {
            return;
        }
        answers.remove(body.attachId() + "/" + body.requestId());
        if (body.ok()) {
            pending.future().complete(null);
        } else {
            pending.future().completeExceptionally(new IllegalStateException(
                    body.error() == null ? "the host refused the answer" : body.error()));
        }
    }

    /** Asks a peer for the agents that a client can attach to. */
    CompletableFuture<List<SwarmHandlers.PeerAgent>> roster(String peerId) {
        var requestId = UUID.randomUUID().toString();
        var future = new CompletableFuture<List<SwarmHandlers.PeerAgent>>();
        rosters.put(requestId, new PendingRoster(peerId, future));
        future.whenComplete((v, e) -> rosters.remove(requestId));
        sender.send(peerId, Messages.AGENT_ROSTER, new RosterBody(requestId, "request", List.of()))
                .whenComplete((v, e) -> {
                    if (e != null) {
                        future.completeExceptionally(unwrap(e));
                    }
                });
        expire(future, "the roster of " + peerId);
        return future;
    }

    /** Opens an attachment to an agent of a peer. */
    RemoteAttachment attach(String peerId, String agentId, Consumer<String> onLine, Runnable onClosed) {
        var attachId = UUID.randomUUID().toString();
        var client = new Client(peerId, attachId, onLine, onClosed);
        clients.put(attachId, client);
        sender.send(peerId, Messages.AGENT_ATTACH, new AttachBody(attachId, "open", agentId, List.of(), null))
                .whenComplete((v, e) -> {
                    if (e != null) {
                        client.opened.completeExceptionally(unwrap(e));
                        clients.remove(attachId);
                    }
                });
        expire(client.opened, "the attach to " + agentId + " on " + peerId);
        return client;
    }

    private void expire(CompletableFuture<?> future, String what) {
        CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute(() ->
                future.completeExceptionally(new TimeoutException(what + " did not answer in "
                        + timeout.toSeconds() + " s")));
    }

    private static Throwable unwrap(Throwable e) {
        return e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
    }

    private final class Client implements RemoteAttachment {
        final String peerId;
        final String attachId;
        final CompletableFuture<List<String>> opened = new CompletableFuture<>();
        private final Consumer<String> sink;
        private final Runnable onClosed;
        private volatile boolean closed;

        Client(String peerId, String attachId, Consumer<String> sink, Runnable onClosed) {
            this.peerId = peerId;
            this.attachId = attachId;
            this.sink = sink;
            this.onClosed = onClosed;
        }

        void onHostFrame(AttachBody body) {
            switch (body.kind()) {
                case "opened" -> opened.complete(body.lines());
                case "error" -> {
                    clients.remove(attachId);
                    opened.completeExceptionally(new IllegalStateException(
                            body.error() == null ? "the host refused the attach" : body.error()));
                }
                case "closed" -> {
                    var was = closed;
                    closed = true;
                    clients.remove(attachId);
                    if (!was && onClosed != null) {
                        onClosed.run();
                    }
                }
                default -> { }
            }
        }

        void onLine(String text) {
            if (!closed) {
                sink.accept(text);
            }
        }

        @Override
        public CompletableFuture<List<String>> opened() {
            return opened;
        }

        @Override
        public void steer(String text) {
            if (!closed) {
                send(peerId, Messages.AGENT_STEER, new SteerBody(attachId, text));
            }
        }

        @Override
        public CompletableFuture<Void> answer(String requestId, String text) {
            var future = new CompletableFuture<Void>();
            if (closed) {
                future.completeExceptionally(new IllegalStateException("the attachment is closed"));
                return future;
            }
            var key = attachId + "/" + requestId;
            answers.put(key, new PendingAnswer(peerId, future));
            future.whenComplete((v, e) -> answers.remove(key));
            sender.send(peerId, Messages.AGENT_ANSWER_REQ, new AnswerReqBody(attachId, requestId, text))
                    .whenComplete((v, e) -> {
                        if (e != null) {
                            future.completeExceptionally(unwrap(e));
                        }
                    });
            expire(future, "the answer to " + requestId);
            return future;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            clients.remove(attachId);
            send(peerId, Messages.AGENT_ATTACH, new AttachBody(attachId, "close", null, List.of(), null));
            opened.completeExceptionally(new IllegalStateException("the attachment is closed"));
        }
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    /** Drops the attachments to and from a peer that is gone. */
    void peerLost(String peerId) {
        hosted.entrySet().removeIf(e -> {
            if (e.getValue().peerId().equals(peerId)) {
                detachQuietly(e.getValue().host());
                return true;
            }
            return false;
        });
        var lost = new IllegalStateException("peer " + peerId + " is gone");
        clients.values().stream().filter(c -> c.peerId.equals(peerId)).toList().forEach(c -> {
            clients.remove(c.attachId);
            c.opened.completeExceptionally(lost);
            if (!c.closed) {
                c.closed = true;
                if (c.onClosed != null) {
                    c.onClosed.run();
                }
            }
        });
        rosters.values().stream().filter(r -> r.peerId().equals(peerId))
                .forEach(r -> r.future().completeExceptionally(lost));
        answers.values().stream().filter(a -> a.peerId().equals(peerId))
                .forEach(a -> a.future().completeExceptionally(lost));
    }

    /** Detaches every host attachment and fails every open call. */
    void close() {
        hosted.values().forEach(h -> detachQuietly(h.host()));
        hosted.clear();
        var lost = new IllegalStateException("the swarm node is closed");
        clients.values().forEach(c -> c.opened.completeExceptionally(lost));
        clients.clear();
        rosters.values().forEach(r -> r.future().completeExceptionally(lost));
        rosters.clear();
        answers.values().forEach(a -> a.future().completeExceptionally(lost));
        answers.clear();
    }
}
