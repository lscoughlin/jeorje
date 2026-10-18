package org.github.lscoughlin.jeorje.swarm;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.core.port.Transport;
import org.github.lscoughlin.jeorje.swarm.Messages.AgentPayload;
import org.github.lscoughlin.jeorje.swarm.Messages.Delegate;
import org.github.lscoughlin.jeorje.swarm.Messages.DigestEntry;
import org.github.lscoughlin.jeorje.swarm.Messages.Gossip;
import org.github.lscoughlin.jeorje.swarm.Messages.HistoryHitView;
import org.github.lscoughlin.jeorje.swarm.Messages.HistoryQueryBody;
import org.github.lscoughlin.jeorje.swarm.Messages.Limits;
import org.github.lscoughlin.jeorje.swarm.Messages.Message;
import org.github.lscoughlin.jeorje.swarm.Messages.PingReq;
import org.github.lscoughlin.jeorje.swarm.Messages.Probe;
import org.github.lscoughlin.jeorje.swarm.SwarmHandlers.DelegateRejectedException;
import org.github.lscoughlin.jeorje.swarm.SwarmHandlers.RunningTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

/**
 * One peer of the swarm. It owns the membership table, runs the SWIM gossip and failure detection, and
 * routes the peer messages. The table has one owner thread. Other threads read an immutable snapshot.
 *
 * <p>The send target of the {@link Transport} is the {@code host:port} address of a peer.
 */
public final class SwarmNode implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SwarmNode.class);

    /** Raised in a future when the peer of a request is lost. */
    public static final class PeerLostException extends RuntimeException {
        public PeerLostException(String message) {
            super(message);
        }
    }

    /** The answer to a remote history search. */
    public record HistoryResult(boolean allowed, List<HistoryHitView> hits) {}

    private record PendingProbe(String targetId) {}

    private record Relay(String originAddress, long originSeq) {}

    private record PendingQuestion(String peerId, CompletableFuture<String> future) {}

    private record PendingHistory(String peerId, CompletableFuture<HistoryResult> future) {}

    private static final class Hosted {
        final String originPeer;
        final String originAddress;
        final AtomicLong seq = new AtomicLong();
        volatile RunningTask task;
        boolean finished;

        Hosted(String originPeer, String originAddress) {
            this.originPeer = originPeer;
            this.originAddress = originAddress;
        }
    }

    private final class Delegation implements RemoteDelegation {
        final String taskId;
        final String peerId;
        final String address;
        final Consumer<DelegateEvent> listener;
        final CompletableFuture<DelegateEvent> completion = new CompletableFuture<>();

        Delegation(String taskId, String peerId, String address, Consumer<DelegateEvent> listener) {
            this.taskId = taskId;
            this.peerId = peerId;
            this.address = address;
            this.listener = listener;
        }

        @Override
        public String taskId() {
            return taskId;
        }

        @Override
        public String peerId() {
            return peerId;
        }

        @Override
        public void steer(String text) {
            sendTo(address, Messages.AGENT_MESSAGE, new AgentPayload("steer", null, taskId, null, null, text));
        }

        @Override
        public void cancel() {
            sendTo(address, Messages.AGENT_MESSAGE, new AgentPayload("cancel", null, taskId, null, null, null));
        }

        @Override
        public void answer(String questionId, String text) {
            sendTo(address, Messages.AGENT_MESSAGE, new AgentPayload("answer", null, taskId, null, questionId, text));
        }

        @Override
        public void answerError(String questionId, String reason) {
            sendTo(address, Messages.AGENT_MESSAGE,
                    new AgentPayload("answer_error", null, taskId, null, questionId, reason));
        }

        @Override
        public CompletableFuture<String> ask(String questionId, String text) {
            var future = new CompletableFuture<String>();
            questions.put(questionId, new PendingQuestion(peerId, future));
            future.whenComplete((v, e) -> questions.remove(questionId));
            sendTo(address, Messages.AGENT_MESSAGE, new AgentPayload("ask", null, taskId, questionId, null, text))
                    .whenComplete((v, e) -> {
                        if (e != null) {
                            future.completeExceptionally(e);
                        }
                    });
            return future;
        }

        @Override
        public CompletableFuture<DelegateEvent> completion() {
            return completion;
        }
    }

    private final SwarmConfig config;
    private final Transport transport;
    private final Clock clock;
    private final SwarmHandlers handlers;
    private final LocalDiscovery discovery;
    private final Membership membership;
    private final String selfId;
    private final String selfAddress;
    private final ScheduledExecutorService owner = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "swarm-owner");
        thread.setDaemon(true);
        return thread;
    });
    private final SerialExecutor callbacks = new SerialExecutor();
    private final Map<String, SerialExecutor> outboxes = new ConcurrentHashMap<>();
    private final Set<String> bootstrap = ConcurrentHashMap.newKeySet();
    private final Map<String, PendingQuestion> questions = new ConcurrentHashMap<>();
    private final Map<String, PendingHistory> histories = new ConcurrentHashMap<>();
    private final Map<String, Delegation> delegations = new ConcurrentHashMap<>();
    private final Map<String, Hosted> hosted = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Map<String, PeerRecord> snapshot;

    // Owner-thread state.
    private long probeSeq;
    private final Map<Long, PendingProbe> probes = new HashMap<>();
    private final Map<Long, Relay> relays = new HashMap<>();

    public SwarmNode(SwarmConfig config, PeerRecord self, Transport transport, Clock clock,
            SwarmHandlers handlers, LocalDiscovery discovery) {
        this.config = config;
        this.transport = transport;
        this.clock = clock;
        this.handlers = handlers;
        this.discovery = discovery;
        this.membership = new Membership(self, config, clock);
        this.selfId = self.peerId();
        this.selfAddress = self.address();
        this.snapshot = membership.snapshot();
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    /** Starts the gossip rounds and contacts the well-known addresses. */
    public SwarmNode start() {
        if (!started.compareAndSet(false, true)) {
            return this;
        }
        bootstrap.addAll(config.wka());
        transport.onMessage(this::receive);
        if (discovery != null) {
            discovery.announce(membership.self());
        }
        owner.scheduleWithFixedDelay(this::round, 0, config.gossipIntervalMs(), TimeUnit.MILLISECONDS);
        return this;
    }

    /** Adds a well-known address and contacts it now. */
    public void join(String address) {
        bootstrap.add(address);
        runOwner(() -> gossipRequest(address));
    }

    /** Sends {@code LEFT}, then stops. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        var flush = new ArrayList<CompletableFuture<Void>>();
        try {
            if (started.get()) {
                Callable<List<CompletableFuture<Void>>> leave = () -> {
                    var left = membership.leave();
                    publish();
                    var futures = new ArrayList<CompletableFuture<Void>>();
                    for (var peer : membership.others()) {
                        if (peer.state().reachable()) {
                            futures.add(sendTo(peer.address(), Messages.GOSSIP,
                                    new Gossip(null, List.of(left), null)));
                        }
                    }
                    return futures;
                };
                flush.addAll(owner.submit(leave).get(2, TimeUnit.SECONDS));
                CompletableFuture.allOf(flush.toArray(CompletableFuture[]::new)).orTimeout(1, TimeUnit.SECONDS)
                        .exceptionally(e -> null).join();
            }
        } catch (Exception e) {
            log.atDebug().log(() -> "leave failed: " + e);
        }
        stop();
    }

    /** Stops at once without sending {@code LEFT}. It simulates a crash. */
    public void kill() {
        if (closed.compareAndSet(false, true)) {
            stop();
        }
    }

    private void stop() {
        owner.shutdownNow();
        var lost = new PeerLostException("the swarm node is closed");
        delegations.values().forEach(d -> d.completion.completeExceptionally(lost));
        delegations.clear();
        questions.values().forEach(q -> q.future().completeExceptionally(lost));
        questions.clear();
        histories.values().forEach(q -> q.future().completeExceptionally(lost));
        histories.clear();
        hosted.values().forEach(h -> cancelQuietly(h.task));
        hosted.clear();
        try {
            transport.close();
        } finally {
            if (discovery != null) {
                discovery.withdraw(selfId);
            }
        }
    }

    // ---- views ----------------------------------------------------------------------------------------

    public String peerId() {
        return selfId;
    }

    public String address() {
        return selfAddress;
    }

    /** The record of this peer. */
    public PeerRecord self() {
        return snapshot.get(selfId);
    }

    /** An immutable snapshot of the membership table, including this peer. */
    public Map<String, PeerRecord> members() {
        return snapshot;
    }

    /** The records of the other peers in the given state. */
    public List<PeerRecord> peers(PeerState state) {
        return snapshot.values().stream()
                .filter(r -> !r.peerId().equals(selfId) && r.state() == state)
                .toList();
    }

    /** Finds a reachable peer with the capability, for example {@code tools:bash}. */
    public Optional<PeerRecord> findPeer(String capability) {
        var matches = snapshot.values().stream()
                .filter(r -> !r.peerId().equals(selfId) && r.state() == PeerState.ALIVE)
                .filter(r -> r.capabilities().contains(capability))
                .toList();
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(matches.get(ThreadLocalRandom.current().nextInt(matches.size())));
    }

    /** Changes the capabilities and the workspace of this peer, and tells the swarm. */
    public void updateSelf(List<String> capabilities, String workspace) {
        runOwner(() -> {
            var record = membership.updateSelf(capabilities, workspace);
            publish();
            gossipRecords(List.of(record), randomAddresses(3));
        });
    }

    // ---- peer API -------------------------------------------------------------------------------------

    /** Sends text to an agent on a peer. */
    public CompletableFuture<Void> sendAgentMessage(String peerId, String toAgent, String text) {
        return sendTo(addressOf(peerId), Messages.AGENT_MESSAGE, new AgentPayload("text", toAgent, null, null, null, text));
    }

    /** Asks a question of an agent on a peer. The answer carries the same question id. */
    public CompletableFuture<String> ask(String peerId, String toAgent, String question, Duration timeout) {
        var address = addressOf(peerId);
        var questionId = UUID.randomUUID().toString();
        var future = new CompletableFuture<String>();
        questions.put(questionId, new PendingQuestion(peerId, future));
        future.whenComplete((v, e) -> questions.remove(questionId));
        sendTo(address, Messages.AGENT_MESSAGE, new AgentPayload("ask", toAgent, null, questionId, null, question))
                .whenComplete((v, e) -> {
                    if (e != null) {
                        future.completeExceptionally(e);
                    }
                });
        future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return future;
    }

    /** Asks a peer to search the history of one of its agents. The remote user rules decide. */
    public CompletableFuture<HistoryResult> historyQuery(String peerId, String text, int limit) {
        var address = addressOf(peerId);
        var queryId = UUID.randomUUID().toString();
        var future = new CompletableFuture<HistoryResult>();
        histories.put(queryId, new PendingHistory(peerId, future));
        future.whenComplete((v, e) -> histories.remove(queryId));
        sendTo(address, Messages.HISTORY_QUERY, new HistoryQueryBody("request", queryId, text, limit, null, null))
                .whenComplete((v, e) -> {
                    if (e != null) {
                        future.completeExceptionally(e);
                    }
                });
        future.orTimeout(config.requestTimeoutMs(), TimeUnit.MILLISECONDS);
        return future;
    }

    /**
     * Starts a task in a remote subagent on a peer.
     *
     * @param listener receives all events, on one serial thread; the last one has the completion summary
     */
    public RemoteDelegation delegate(String peerId, String task, List<String> allowedTools, Limits limits,
            Consumer<DelegateEvent> listener) {
        var address = addressOf(peerId);
        var taskId = UUID.randomUUID().toString();
        var delegation = new Delegation(taskId, peerId, address, listener);
        delegations.put(taskId, delegation);
        sendTo(address, Messages.DELEGATE, new Delegate(taskId, task, allowedTools, limits))
                .whenComplete((v, e) -> {
                    if (e != null && delegations.remove(taskId) != null) {
                        delegation.completion.completeExceptionally(e);
                    }
                });
        return delegation;
    }

    private String addressOf(String peerId) {
        var record = snapshot.get(peerId);
        if (record == null || !record.state().reachable() || peerId.equals(selfId)) {
            throw new IllegalArgumentException("unknown or unreachable peer: " + peerId);
        }
        return record.address();
    }

    // ---- sending --------------------------------------------------------------------------------------

    private String encode(String type, Object body) {
        var message = new Message(Handshake.PROTOCOL_VERSION, type, selfId, selfAddress,
                UUID.randomUUID().toString(), clock.millis(), Json.MAPPER.valueToTree(body));
        var json = Json.MAPPER.writeValueAsString(message);
        if (json.length() > config.maxMessageBytes()) {
            throw new IllegalArgumentException(
                    "message of " + json.length() + " characters is above " + config.maxMessageBytes());
        }
        return json;
    }

    private CompletableFuture<Void> sendTo(String address, String type, Object body) {
        var json = encode(type, body);
        var outbox = outboxes.computeIfAbsent(address, a -> new SerialExecutor());
        var future = new CompletableFuture<Void>();
        outbox.execute(() -> {
            try {
                transport.send(address, json);
                future.complete(null);
            } catch (RuntimeException e) {
                log.atDebug().log(() -> "send to " + address + " failed: " + e.getMessage());
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private void runOwner(Runnable task) {
        try {
            owner.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.atWarn().log(() -> "owner task failed: " + e);
                } finally {
                    publish();
                }
            });
        } catch (RejectedExecutionException e) {
            // The node is closed.
        }
    }

    // ---- receiving ------------------------------------------------------------------------------------

    private void receive(Transport.Envelope envelope) {
        Message message;
        try {
            message = Json.MAPPER.readValue(envelope.body(), Message.class);
        } catch (JacksonException e) {
            log.atDebug().log(() -> "bad message from " + envelope.peerId());
            return;
        }
        if (message.v() != Handshake.PROTOCOL_VERSION || message.type() == null || message.from() == null) {
            log.atDebug().log(() -> "message with a wrong version or no sender from " + envelope.peerId());
            return;
        }
        if (envelope.peerId() != null && !envelope.peerId().equals(message.from())) {
            log.atWarn().log(() -> "sender mismatch: the connection is " + envelope.peerId());
            return;
        }
        if (message.from().equals(selfId)) {
            return;
        }
        final var accepted = message;
        runOwner(() -> dispatch(accepted));
    }

    private <T> T body(Message message, Class<T> type) {
        return Json.MAPPER.convertValue(message.body(), type);
    }

    private void dispatch(Message message) {
        try {
            switch (message.type()) {
                case Messages.GOSSIP -> onGossip(message, body(message, Gossip.class));
                case Messages.PING -> onPing(message, body(message, Probe.class));
                case Messages.ACK -> onAck(message, body(message, Probe.class));
                case Messages.PING_REQ -> onPingReq(message, body(message, PingReq.class));
                case Messages.AGENT_MESSAGE -> {
                    var payload = body(message, AgentPayload.class);
                    callbacks.execute(() -> onAgentMessage(message, payload));
                }
                case Messages.DELEGATE -> {
                    var payload = body(message, Delegate.class);
                    callbacks.execute(() -> onDelegate(message, payload));
                }
                case Messages.DELEGATE_EVENT -> {
                    var payload = body(message, DelegateEvent.class);
                    callbacks.execute(() -> onDelegateEvent(message, payload));
                }
                case Messages.HISTORY_QUERY -> {
                    var payload = body(message, HistoryQueryBody.class);
                    callbacks.execute(() -> onHistoryQuery(message, payload));
                }
                default -> log.atDebug().log(() -> "unknown message type ignored: " + message.type());
            }
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "cannot handle " + message.type() + ": " + e);
        }
    }

    // ---- membership: rounds ---------------------------------------------------------------------------

    private List<PeerRecord> reachablePeers() {
        return membership.others().stream().filter(r -> r.state().reachable()).toList();
    }

    private PeerRecord random(List<PeerRecord> list) {
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    private List<String> randomAddresses(int count) {
        var list = new ArrayList<>(reachablePeers());
        Collections.shuffle(list);
        return list.stream().limit(count).map(PeerRecord::address).toList();
    }

    private void round() {
        try {
            membership.expire();
            var reachable = reachablePeers();
            if (reachable.isEmpty()) {
                contactBootstrap();
            } else {
                gossipRequest(random(reachable).address());
                probe(random(reachable));
            }
            var dead = membership.others().stream().filter(r -> r.state() == PeerState.DEAD).toList();
            if (!dead.isEmpty() && ThreadLocalRandom.current().nextInt(reachable.size() + 1) == 0) {
                gossipRequest(random(dead).address());
            }
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "round failed: " + e);
        } finally {
            publish();
        }
    }

    private void contactBootstrap() {
        var targets = new LinkedHashSet<>(bootstrap);
        if (discovery != null) {
            targets.addAll(discovery.addresses(selfId));
        }
        targets.remove(selfAddress);
        targets.forEach(this::gossipRequest);
    }

    private List<DigestEntry> digest() {
        var list = new ArrayList<DigestEntry>();
        for (var record : membership.snapshot().values()) {
            list.add(new DigestEntry(record.peerId(), record.incarnation(), record.state()));
        }
        return list;
    }

    private void gossipRequest(String address) {
        sendQuietly(address, Messages.GOSSIP, new Gossip(digest(), List.of(membership.self()), null));
    }

    private void gossipRecords(List<PeerRecord> records, List<String> addresses) {
        var gossip = new Gossip(null, records, null);
        addresses.forEach(a -> sendQuietly(a, Messages.GOSSIP, gossip));
    }

    private void sendQuietly(String address, String type, Object body) {
        sendTo(address, type, body).exceptionally(e -> null);
    }

    private void probe(PeerRecord target) {
        var seq = ++probeSeq;
        probes.put(seq, new PendingProbe(target.peerId()));
        sendQuietly(target.address(), Messages.PING, new Probe(seq, membership.self()));
        schedule(() -> indirect(seq, target), config.pingTimeoutMs());
    }

    private void indirect(long seq, PeerRecord target) {
        if (!probes.containsKey(seq)) {
            return;
        }
        var helpers = new ArrayList<>(reachablePeers().stream()
                .filter(r -> !r.peerId().equals(target.peerId())).toList());
        Collections.shuffle(helpers);
        helpers.stream().limit(config.indirectPings()).forEach(helper -> sendQuietly(helper.address(),
                Messages.PING_REQ, new PingReq(seq, target.peerId(), target.address())));
        schedule(() -> probeFailed(seq), config.pingTimeoutMs());
    }

    private void probeFailed(long seq) {
        var probe = probes.remove(seq);
        if (probe == null) {
            return;
        }
        var suspect = membership.suspect(probe.targetId());
        if (suspect != null) {
            log.atDebug().log(() -> selfId + " suspects " + suspect.peerId());
            var targets = new ArrayList<String>();
            targets.add(suspect.address());
            targets.addAll(randomAddresses(2));
            gossipRecords(List.of(suspect), targets.stream().distinct().toList());
        }
    }

    private void schedule(Runnable task, long delayMs) {
        try {
            owner.schedule(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.atWarn().log(() -> "scheduled task failed: " + e);
                } finally {
                    publish();
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // The node is closed.
        }
    }

    // ---- membership: messages -------------------------------------------------------------------------

    private void mergeRecord(PeerRecord record, String replyAddress) {
        if (membership.merge(record) == Membership.Merge.REFUTED) {
            var targets = new ArrayList<>(randomAddresses(3));
            if (replyAddress != null && !targets.contains(replyAddress)) {
                targets.add(replyAddress);
            }
            gossipRecords(List.of(membership.self()), targets);
        }
    }

    private void onGossip(Message message, Gossip gossip) {
        var from = message.fromAddr();
        if (gossip.records() != null) {
            gossip.records().forEach(r -> mergeRecord(r, from));
        }
        if (gossip.digest() != null) {
            var self = membership.self();
            var theirs = new HashMap<String, DigestEntry>();
            for (var entry : gossip.digest()) {
                theirs.put(entry.peerId(), entry);
                if (entry.peerId().equals(selfId)) {
                    mergeRecord(self.withIncarnation(entry.incarnation()).withState(entry.state()), from);
                }
            }
            var out = new ArrayList<PeerRecord>();
            for (var record : membership.snapshot().values()) {
                var entry = theirs.get(record.peerId());
                if (entry == null || record.newerThan(entry.incarnation(), entry.state())) {
                    out.add(record);
                }
            }
            var want = new ArrayList<String>();
            for (var entry : gossip.digest()) {
                if (entry.peerId().equals(selfId)) {
                    continue;
                }
                var current = membership.get(entry.peerId());
                if (current == null) {
                    if (entry.state().reachable()) {
                        want.add(entry.peerId());
                    }
                } else if (entry.incarnation() > current.incarnation() || (entry.incarnation() == current.incarnation()
                        && entry.state().severity() > current.state().severity())) {
                    want.add(entry.peerId());
                }
            }
            if (from != null) {
                sendQuietly(from, Messages.GOSSIP, new Gossip(null, out, want));
            }
        } else if (gossip.want() != null && !gossip.want().isEmpty() && from != null) {
            var out = new ArrayList<PeerRecord>();
            for (var id : gossip.want()) {
                var record = membership.get(id);
                if (record != null) {
                    out.add(record);
                }
            }
            sendQuietly(from, Messages.GOSSIP, new Gossip(null, out, null));
        }
    }

    private void onPing(Message message, Probe probe) {
        if (probe.self() != null) {
            mergeRecord(probe.self(), message.fromAddr());
        }
        sendQuietly(message.fromAddr(), Messages.ACK, new Probe(probe.seq(), membership.self()));
    }

    private void onAck(Message message, Probe ack) {
        if (ack.self() != null) {
            mergeRecord(ack.self(), message.fromAddr());
        }
        var relay = relays.remove(ack.seq());
        if (relay != null) {
            sendQuietly(relay.originAddress(), Messages.ACK, new Probe(relay.originSeq(), ack.self()));
        }
        probes.remove(ack.seq());
    }

    private void onPingReq(Message message, PingReq request) {
        var seq = ++probeSeq;
        relays.put(seq, new Relay(message.fromAddr(), request.seq()));
        sendQuietly(request.targetAddress(), Messages.PING, new Probe(seq, membership.self()));
        schedule(() -> relays.remove(seq), config.pingTimeoutMs() * 2);
    }

    private void publish() {
        var next = membership.snapshot();
        var previous = snapshot;
        snapshot = next;
        for (var record : next.values()) {
            if (record.peerId().equals(selfId) || record.equals(previous.get(record.peerId()))) {
                continue;
            }
            callbacks.execute(() -> handlers.onPeerChange(record));
            if (record.state() == PeerState.DEAD || record.state() == PeerState.LEFT) {
                peerLost(record);
            }
        }
    }

    private void peerLost(PeerRecord record) {
        var id = record.peerId();
        var lost = new PeerLostException("peer " + id + " is " + record.state());
        delegations.values().stream().filter(d -> d.peerId.equals(id)).forEach(d -> {
            if (delegations.remove(d.taskId) != null) {
                d.completion.completeExceptionally(lost);
            }
        });
        questions.values().stream().filter(q -> q.peerId().equals(id))
                .forEach(q -> q.future().completeExceptionally(lost));
        histories.values().stream().filter(q -> q.peerId().equals(id))
                .forEach(q -> q.future().completeExceptionally(lost));
        hosted.entrySet().removeIf(e -> {
            if (e.getValue().originPeer.equals(id)) {
                var task = e.getValue().task;
                callbacks.execute(() -> cancelQuietly(task));
                return true;
            }
            return false;
        });
    }

    private void cancelQuietly(RunningTask task) {
        if (task != null) {
            try {
                task.cancel();
            } catch (RuntimeException e) {
                log.atDebug().log(() -> "cancel failed: " + e);
            }
        }
    }

    // ---- agent messages -------------------------------------------------------------------------------

    private void onAgentMessage(Message message, AgentPayload payload) {
        var kind = payload.kind() == null ? "text" : payload.kind();
        switch (kind) {
            case "text" -> handlers.onAgentMessage(message.from(), payload.toAgent(), payload.text());
            case "ask" -> {
                var from = message.fromAddr();
                var questionId = payload.questionId();
                if (payload.taskId() != null) {
                    askTask(message, payload);
                    return;
                }
                handlers.onAgentAsk(message.from(), payload.toAgent(), payload.text()).whenComplete((answer, error) -> {
                    var reply = error == null
                            ? new AgentPayload("answer", null, null, null, questionId, answer)
                            : new AgentPayload("answer_error", null, null, null, questionId, String.valueOf(error.getMessage()));
                    sendQuietly(from, Messages.AGENT_MESSAGE, reply);
                });
            }
            case "answer", "answer_error" -> {
                var task = payload.taskId() == null ? null : ownedTask(message, payload.taskId());
                if (task != null) {
                    if (kind.equals("answer")) {
                        task.answer(payload.answerTo(), payload.text());
                    } else {
                        task.answerError(payload.answerTo(), payload.text());
                    }
                    return;
                }
                var question = questions.get(payload.answerTo());
                if (question != null && question.peerId().equals(message.from())) {
                    questions.remove(payload.answerTo());
                    if (kind.equals("answer")) {
                        question.future().complete(payload.text());
                    } else {
                        question.future().completeExceptionally(new IllegalStateException(payload.text()));
                    }
                }
            }
            case "steer" -> {
                var task = ownedTask(message, payload.taskId());
                if (task != null) {
                    task.steer(payload.text());
                }
            }
            case "cancel" -> cancelQuietly(ownedTask(message, payload.taskId()));
            default -> log.atDebug().log(() -> "unknown agent_message kind ignored: " + kind);
        }
    }

    /** A question from the delegating peer to a hosted task. The answer or the refusal goes back with the same id. */
    private void askTask(Message message, AgentPayload payload) {
        var from = message.fromAddr();
        var questionId = payload.questionId();
        var task = ownedTask(message, payload.taskId());
        if (task == null) {
            sendQuietly(from, Messages.AGENT_MESSAGE,
                    new AgentPayload("answer_error", null, null, null, questionId, "unknown agent"));
            return;
        }
        CompletionStage<String> stage;
        try {
            stage = task.ask(questionId, payload.text());
        } catch (RuntimeException e) {
            stage = CompletableFuture.failedFuture(e);
        }
        stage.whenComplete((answer, error) -> {
            var cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            var reply = error == null
                    ? new AgentPayload("answer", null, null, null, questionId, answer)
                    : new AgentPayload("answer_error", null, null, null, questionId,
                            cause.getMessage() == null ? "ended" : cause.getMessage());
            sendQuietly(from, Messages.AGENT_MESSAGE, reply);
        });
    }

    /** Returns the running task if the message comes from the peer that started it. */
    private RunningTask ownedTask(Message message, String taskId) {
        if (taskId == null) {
            return null;
        }
        var entry = hosted.get(taskId);
        if (entry == null || !entry.originPeer.equals(message.from())) {
            return null;
        }
        return entry.task;
    }

    // ---- delegation -----------------------------------------------------------------------------------

    private void onDelegate(Message message, Delegate request) {
        var taskId = request.taskId();
        var origin = new Hosted(message.from(), message.fromAddr());
        var host = handlers.delegateHost();
        if (host == null) {
            reply(origin, taskId, DelegateEvent.rejected(taskId, "this peer does not accept delegated tasks"));
            return;
        }
        if (hosted.putIfAbsent(taskId, origin) != null) {
            return;
        }
        Consumer<DelegateEvent> sink = event -> {
            synchronized (origin) {
                if (origin.finished) {
                    return;
                }
                if (event.last()) {
                    origin.finished = true;
                    hosted.remove(taskId);
                }
                reply(origin, taskId, event);
            }
        };
        try {
            origin.task = host.accept(message.from(), request, sink);
        } catch (DelegateRejectedException e) {
            sink.accept(DelegateEvent.rejected(taskId, e.getMessage()));
        } catch (RuntimeException e) {
            sink.accept(DelegateEvent.error(taskId, String.valueOf(e)));
        }
    }

    private void reply(Hosted origin, String taskId, DelegateEvent event) {
        sendQuietly(origin.originAddress, Messages.DELEGATE_EVENT, event.stamp(taskId, origin.seq.incrementAndGet()));
    }

    private void onDelegateEvent(Message message, DelegateEvent event) {
        var delegation = delegations.get(event.taskId());
        if (delegation == null || !delegation.peerId.equals(message.from())) {
            return;
        }
        try {
            delegation.listener.accept(event);
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "delegate listener failed: " + e);
        }
        if (event.last() && delegations.remove(event.taskId()) != null) {
            delegation.completion.complete(event);
        }
    }

    // ---- history --------------------------------------------------------------------------------------

    private void onHistoryQuery(Message message, HistoryQueryBody query) {
        if ("result".equals(query.kind())) {
            var pending = histories.get(query.queryId());
            if (pending != null && pending.peerId().equals(message.from())) {
                histories.remove(query.queryId());
                pending.future().complete(new HistoryResult(Boolean.TRUE.equals(query.allowed()),
                        query.hits() == null ? List.of() : query.hits()));
            }
            return;
        }
        Optional<List<HistoryHitView>> hits;
        try {
            hits = handlers.onHistoryQuery(message.from(), query.text(), query.limit());
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "history query failed: " + e);
            hits = Optional.empty();
        }
        var body = new HistoryQueryBody("result", query.queryId(), null, 0, hits.isPresent(),
                hits.orElse(List.of()));
        sendQuietly(message.fromAddr(), Messages.HISTORY_QUERY, body);
    }
}
