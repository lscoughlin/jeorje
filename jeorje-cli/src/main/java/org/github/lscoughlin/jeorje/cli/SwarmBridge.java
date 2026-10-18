package org.github.lscoughlin.jeorje.cli;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.github.lscoughlin.jeorje.core.agent.AgentInput;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.permissions.Rule;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.Filters;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.Syntax;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.Transport;
import org.github.lscoughlin.jeorje.core.subagent.Completion;
import org.github.lscoughlin.jeorje.core.subagent.RemoteSpawner;
import org.github.lscoughlin.jeorje.core.subagent.SpawnException;
import org.github.lscoughlin.jeorje.core.subagent.SpawnRequest;
import org.github.lscoughlin.jeorje.core.subagent.SubagentEvent;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.swarm.DelegateEvent;
import org.github.lscoughlin.jeorje.swarm.Messages;
import org.github.lscoughlin.jeorje.swarm.PeerIdStore;
import org.github.lscoughlin.jeorje.swarm.PeerRecord;
import org.github.lscoughlin.jeorje.swarm.PeerState;
import org.github.lscoughlin.jeorje.swarm.SwarmConfig;
import org.github.lscoughlin.jeorje.swarm.SwarmHandlers;
import org.github.lscoughlin.jeorje.swarm.SwarmNode;
import org.github.lscoughlin.jeorje.swarm.TcpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Connects one {@link SwarmNode} to the agents of this instance.
 *
 * <p>As a client, it is the {@link RemoteSpawner} of the subagent runtime. A remote spawn becomes a delegation
 * to a peer. The runtime shows the remote events as a local subagent.
 *
 * <p>As a host, it is the {@link SwarmHandlers.DelegateHost}. A delegated task runs as a subagent of a hidden
 * root agent, with the permission policy of this instance. A peer message never approves a tool run. A history
 * query from a peer is denied unless a user rule for the tool {@code history_query} allows it.
 */
public final class SwarmBridge implements SwarmHandlers, SwarmHandlers.DelegateHost, RemoteSpawner, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SwarmBridge.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The name of the permission rule tool for history queries from peers. */
    public static final String HISTORY_RULE_TOOL = "history_query";
    /** The id of the hidden root agent of the tasks that peers delegate to this instance. */
    public static final AgentId HOST_ROOT = new AgentId("remote");

    private record Hosted(String taskId, Consumer<DelegateEvent> sink, AtomicBoolean done, String peer) {}

    private final SwarmConfig swarmConfig;
    private final Transport transport;
    private final PeerRecord self;
    private final Clock clock;
    private final Consumer<String> notice;
    private final Runnable onChange;
    private final Predicate<String> historyAllowed;
    private final ConcurrentHashMap<AgentId, Hosted> hosted = new ConcurrentHashMap<>();
    private final AtomicInteger hostedCounter = new AtomicInteger();
    private volatile List<String> capabilities = List.of();
    private volatile SwarmNode node;
    private volatile SubagentRuntime runtime;
    private volatile PermissionPolicy hostPolicy = action -> PermissionPolicy.Decision.ASK;
    private volatile Consumer<AgentInput> mainInput = input -> { };
    private volatile Supplier<ContextStore> store = () -> null;

    /**
     * @param transport the transport of the node
     * @param self the record of this peer
     * @param historyAllowed tells if a peer (by id or name) may search the history of this instance
     * @param notice shows a line to the user
     * @param onChange is called when a peer changes
     */
    public SwarmBridge(SwarmConfig swarmConfig, Transport transport, PeerRecord self, Clock clock,
                       Predicate<String> historyAllowed, Consumer<String> notice, Runnable onChange) {
        this.swarmConfig = swarmConfig;
        this.transport = transport;
        this.self = self;
        this.clock = clock;
        this.historyAllowed = historyAllowed;
        this.notice = notice == null ? text -> { } : notice;
        this.onChange = onChange == null ? () -> { } : onChange;
    }

    /** True if the config asks for the swarm. */
    public static boolean wanted(JeorjeConfig config) { return config.swarm().enabled(); }

    /**
     * Makes the bridge with a TCP transport. If the bind port is in use, it takes a free port on the same host.
     */
    public static SwarmBridge forConfig(JeorjeConfig config, Path home, Path cwd, Consumer<String> notice,
                                        Runnable onChange) {
        var cfg = config.swarm();
        var base = SwarmConfig.defaults().withBind(cfg.bind()).withWka(cfg.wka()).withToken(cfg.token())
                .withGossipIntervalMs(cfg.gossipIntervalMs());
        var peerId = PeerIdStore.loadOrCreate(home.resolve(".jeorje"));
        TcpTransport tcp;
        try {
            tcp = new TcpTransport(base, peerId, null);
        } catch (RuntimeException e) {
            var failed = base.bind();
            var host = failed.substring(0, Math.max(0, failed.lastIndexOf(':')));
            log.atWarn().log(() -> "Cannot bind " + failed + ": " + e.getMessage() + ". Using a free port.");
            base = base.withBind(host + ":0");
            tcp = new TcpTransport(base, peerId, null);
        }
        var name = cwd.getFileName() == null ? "jeorje" : cwd.getFileName().toString();
        var record = PeerRecord.of(peerId, tcp.address(), name);
        return new SwarmBridge(base, tcp, record, Clock.systemUTC(), historyRule(config), notice, onChange);
    }

    /** The history rule of the config: only an explicit user rule for {@code history_query} allows a peer. */
    public static Predicate<String> historyRule(JeorjeConfig config) {
        var rules = config.permissions().rules().stream().map(Rule::from).toList();
        return peer -> Rule.resolve(rules, HISTORY_RULE_TOOL, List.of(peer)) == PermissionPolicy.Decision.ALLOW;
    }

    /**
     * Connects the bridge to the agents of this instance. Call it before {@link #start}.
     *
     * @param runtime the runtime that runs the delegated tasks
     * @param hostTools the tools that a delegated task can get
     * @param policy the permission policy of this instance for delegated tasks
     * @param mainInput receives the messages from peers for the agent of the user
     * @param store the history of this instance, for history queries
     */
    public void attach(SubagentRuntime runtime, List<Tool> hostTools, PermissionPolicy policy,
                       Consumer<AgentInput> mainInput, Supplier<ContextStore> store) {
        this.runtime = runtime;
        this.hostPolicy = policy;
        this.mainInput = mainInput;
        this.store = store;
        runtime.registerRoot(HOST_ROOT, "remote", new CancellationToken(), hostTools, policy, input -> { },
                () -> 1);
        var caps = new ArrayList<String>();
        hostTools.forEach(t -> caps.add("tools:" + t.definition().name()));
        capabilities = List.copyOf(caps);
    }

    /** Starts the node and joins the swarm. */
    public SwarmBridge start() {
        var started = new SwarmNode(swarmConfig, self.withCapabilities(capabilities), transport, clock, this, null);
        node = started.start();
        return this;
    }

    public SwarmNode node() { return node; }

    /** The node, or null when the bridge did not start. */
    public String address() { return node == null ? self.address() : node.address(); }

    // ---- the client side: RemoteSpawner ----

    @Override
    public Optional<Peer> resolve(String peer, String capability) {
        var n = node;
        if (n == null) return Optional.empty();
        if (peer != null) {
            return n.peers(PeerState.ALIVE).stream()
                    .filter(p -> p.peerId().equals(peer) || p.name().equalsIgnoreCase(peer) || p.address().equals(peer))
                    .findFirst().map(p -> new Peer(p.peerId(), p.name()));
        }
        return n.findPeer(capability).map(p -> new Peer(p.peerId(), p.name()));
    }

    @Override
    public Task start(Peer peer, String task, List<String> tools, int maxModelCalls, int timeoutSeconds,
                      Consumer<Event> listener) {
        var delegation = node.delegate(peer.id(), task, tools, new Messages.Limits(maxModelCalls, timeoutSeconds),
                event -> listener.accept(convert(event)));
        return new Task() {
            @Override public void steer(String text) { delegation.steer(text); }

            @Override public void cancel() { delegation.cancel(); }

            @Override public void answer(String questionId, String text) { delegation.answer(questionId, text); }

            @Override public java.util.concurrent.CompletableFuture<Event> completion() {
                return delegation.completion().thenApply(SwarmBridge::convert);
            }
        };
    }

    private static Event convert(DelegateEvent event) {
        var kind = switch (event.kind()) {
            case PROGRESS -> Kind.PROGRESS;
            case QUESTION -> Kind.QUESTION;
            case RESULT -> Kind.RESULT;
            case REJECTED -> Kind.REJECTED;
            case ERROR -> Kind.ERROR;
        };
        return new Event(kind, event.text(), event.questionId(), event.last(), event.outcome(), event.summary());
    }

    // ---- the host side: SwarmHandlers ----

    @Override
    public DelegateHost delegateHost() { return this; }

    @Override
    public RunningTask accept(String fromPeer, Messages.Delegate request, Consumer<DelegateEvent> sink) {
        var rt = runtime;
        if (rt == null) throw new DelegateRejectedException("this peer cannot run delegated tasks");
        var tools = request.allowedTools() == null ? List.<String>of() : request.allowedTools();
        denyIfPolicyDenies("spawn_agent");
        for (var tool : tools) denyIfPolicyDenies(tool);
        var peerName = nameOf(fromPeer);
        var name = "peer-" + peerName + "-" + hostedCounter.incrementAndGet();
        var spawn = SpawnRequest.of(name, request.task()).withTools(tools.isEmpty() ? null : tools);
        AgentId id;
        var entry = new Hosted(request.taskId(), sink, new AtomicBoolean(), peerName);
        try {
            id = rt.spawn(HOST_ROOT, spawn);
        } catch (SpawnException e) {
            throw new DelegateRejectedException(e.getMessage());
        }
        hosted.put(id, entry);
        notice.accept("Peer " + peerName + " delegated a task: " + shorten(request.task()));
        // The task can end before the entry exists. Send the result now in that case.
        rt.completion(id).ifPresent(completion -> finishHosted(id, completion));
        var limit = request.limits();
        log.atInfo().log(() -> "accepted task " + request.taskId() + " from " + peerName + " as " + id
                + (limit == null ? "" : " (limits " + limit + ")"));
        return new RunningTask() {
            @Override public void steer(String text) { rt.steer(id, text); }

            @Override public void cancel() { rt.cancel(id.value()); }

            @Override public void answer(String questionId, String text) {
                rt.steer(id, "[answer to question " + questionId + " from the delegating peer]\n" + text);
            }
        };
    }

    private void denyIfPolicyDenies(String tool) {
        try {
            var decision = hostPolicy.check(new ToolUse("delegate-check", tool, MAPPER.createObjectNode()));
            if (decision == PermissionPolicy.Decision.DENY) {
                throw new DelegateRejectedException("the permission policy of this peer denies the tool " + tool);
            }
        } catch (DelegateRejectedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.atDebug().log(() -> "policy probe for " + tool + " failed: " + e);
        }
    }

    /** Call it for each event of a subagent loop. It sends progress to the delegating peer. */
    public void onAgentEvent(AgentEvent event) {
        var entry = hosted.get(event.agent());
        if (entry == null || entry.done().get()) return;
        switch (event) {
            case AgentEvent.ToolStarted s -> entry.sink().accept(DelegateEvent.progress(entry.taskId(), s.summary()));
            case AgentEvent.TurnFinished t -> entry.sink().accept(DelegateEvent.progress(entry.taskId(),
                    "turn " + t.turn() + " finished"));
            default -> { }
        }
    }

    /** Call it for each event of the subagent runtime. It sends the last event to the delegating peer. */
    public void onSubagentEvent(SubagentEvent event) {
        if (event instanceof SubagentEvent.Finished f && hosted.containsKey(f.agent())) {
            finishHosted(f.agent(), f.completion());
        }
    }

    private void finishHosted(AgentId id, Completion completion) {
        var entry = hosted.get(id);
        if (entry == null || !entry.done().compareAndSet(false, true)) return;
        hosted.remove(id);
        var outcome = completion.outcome().name().toLowerCase(java.util.Locale.ROOT);
        entry.sink().accept(DelegateEvent.result(entry.taskId(), outcome,
                completion.finalText() == null ? "" : completion.finalText(), completion.summary()));
    }

    @Override
    public void onAgentMessage(String fromPeer, String toAgent, String text) {
        mainInput.accept(new AgentInput.PeerMessage(new AgentId("peer:" + nameOf(fromPeer)), text));
    }

    @Override
    public Optional<List<Messages.HistoryHitView>> onHistoryQuery(String fromPeer, String text, int limit) {
        var name = nameOf(fromPeer);
        if (!historyAllowed.test(fromPeer) && !historyAllowed.test(name)) {
            log.atInfo().log(() -> "history query from " + name + " denied");
            return Optional.empty();
        }
        var history = store.get();
        if (history == null) return Optional.empty();
        var hits = history.search(new HistoryQuery(text, Syntax.HYBRID, Filters.none(), Math.max(1, Math.min(limit, 20))));
        return Optional.of(hits.stream().map(h -> new Messages.HistoryHitView(h.chunk().agentId(), h.snippet(),
                h.score())).toList());
    }

    @Override
    public void onPeerChange(PeerRecord record) { onChange.run(); }

    private String nameOf(String peerId) {
        var n = node;
        if (n == null) return peerId;
        var record = n.members().get(peerId);
        return record == null || record.name().isBlank() ? peerId : record.name();
    }

    private static String shorten(String text) {
        var line = text.strip().replace('\n', ' ');
        return line.length() > 80 ? line.substring(0, 80) + "..." : line;
    }

    @Override
    public void close() {
        var n = node;
        if (n != null) {
            try {
                n.close();
            } catch (RuntimeException e) {
                log.atWarn().log(() -> "Cannot close the swarm: " + e);
            }
        } else {
            transport.close();
        }
    }
}
