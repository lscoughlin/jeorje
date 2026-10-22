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
import org.github.lscoughlin.jeorje.swarm.CompositeDiscovery;
import org.github.lscoughlin.jeorje.swarm.DelegateEvent;
import org.github.lscoughlin.jeorje.swarm.LocalRendezvous;
import org.github.lscoughlin.jeorje.swarm.Messages;
import org.github.lscoughlin.jeorje.swarm.PeerDiscovery;
import org.github.lscoughlin.jeorje.swarm.ZeroconfDiscovery;
import org.github.lscoughlin.jeorje.swarm.PeerIdStore;
import org.github.lscoughlin.jeorje.swarm.PeerRecord;
import org.github.lscoughlin.jeorje.swarm.PeerState;
import org.github.lscoughlin.jeorje.swarm.SwarmConfig;
import org.github.lscoughlin.jeorje.swarm.SwarmHandlers;
import org.github.lscoughlin.jeorje.swarm.SshChannelTransport;
import org.github.lscoughlin.jeorje.swarm.SwarmNode;
import org.github.lscoughlin.jeorje.swarm.SwarmSshServer;
import org.github.lscoughlin.jeorje.swarm.SwarmTokenStore;
import org.github.lscoughlin.jeorje.swarm.TcpTransport;
import org.github.lscoughlin.jeorje.swarm.TlsSupport;
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
    private volatile PeerDiscovery discovery;
    private volatile PeerAnswerer answerer;
    private volatile AgentAccess access;
    private volatile SwarmSshServer sshServer;
    private volatile SshChannelTransport sshTransport;
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
        return forConfig(config, home, cwd, java.util.Map.of(), notice, onChange);
    }

    /**
     * Makes the bridge with a TCP transport. The key {@code swarm.tls} wraps the transport in TLS. The key
     * {@code swarm.discovery} turns on zeroconf (mDNS) discovery. If zeroconf cannot start, the swarm runs without it.
     *
     * @param environment the environment variables, for the password of the TLS key store
     */
    public static SwarmBridge forConfig(JeorjeConfig config, Path home, Path cwd,
                                        java.util.Map<String, String> environment, Consumer<String> notice,
                                        Runnable onChange) {
        var cfg = config.swarm();
        var stateDir = home.resolve(".jeorje");
        // The token is resolved here and only here. A blank config token means the token file of the state directory.
        var token = SwarmTokenStore.resolve(stateDir, cfg.token());
        var base = SwarmConfig.defaults().withBind(cfg.bind()).withWka(cfg.wka()).withToken(token)
                .withGossipIntervalMs(cfg.gossipIntervalMs());
        var parts = new ArrayList<PeerDiscovery>();
        if (cfg.rendezvous()) parts.add(new LocalRendezvous(stateDir, Clock.systemUTC()));
        if (cfg.discovery()) {
            var zeroconf = zeroconf();
            if (zeroconf != null) parts.add(zeroconf);
        }
        var discovery = parts.isEmpty() ? null : new CompositeDiscovery(parts);
        // Another instance of the same user can be live and use the saved id. This instance takes its own id then.
        var live = discovery == null ? java.util.Set.<String>of()
                : discovery.peers("").stream().map(PeerDiscovery.Entry::peerId).collect(java.util.stream.Collectors.toSet());
        var peerId = PeerIdStore.fresh(stateDir, live);
        var tls = tlsContext(cfg.tls(), cwd, environment);
        TcpTransport tcp;
        try {
            tcp = new TcpTransport(base, peerId, tls);
        } catch (RuntimeException e) {
            var failed = base.bind();
            var host = failed.substring(0, Math.max(0, failed.lastIndexOf(':')));
            log.atWarn().log(() -> "Cannot bind " + failed + ": " + e.getMessage() + ". Using a free port.");
            base = base.withBind(host + ":0");
            tcp = new TcpTransport(base, peerId, tls);
        }
        var name = cwd.getFileName() == null ? "jeorje" : cwd.getFileName().toString();
        var record = PeerRecord.of(peerId, tcp.address(), name);
        var bridge = new SwarmBridge(base, tcp, record, Clock.systemUTC(), historyRule(config), notice, onChange)
                .withDiscovery(discovery);
        if (cfg.ssh().enabled()) {
            try {
                bridge.withSsh(new SwarmSshServer(cfg.ssh().bind(), cfg.ssh().advertiseHost(), token));
            } catch (RuntimeException e) {
                log.atWarn().log(() -> "The SSH channel did not start: " + e.getMessage());
            }
        }
        return bridge;
    }

    /** Starts zeroconf discovery and waits a short time for the first answers. Returns null on failure. */
    private static ZeroconfDiscovery zeroconf() {
        try {
            var found = new ZeroconfDiscovery();
            found.settle(500);
            return found;
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "Zeroconf discovery is not available: " + e.getMessage());
            return null;
        }
    }

    /** The TLS context of the config, or null when {@code swarm.tls.keyStore} is empty. */
    static javax.net.ssl.SSLContext tlsContext(JeorjeConfig.Tls tls, Path cwd,
                                               java.util.Map<String, String> environment) {
        if (!tls.enabled()) return null;
        var password = new char[0];
        if (!tls.passwordEnv().isBlank()) {
            var value = environment.get(tls.passwordEnv());
            if (value == null) {
                throw new CliException("swarm.tls.passwordEnv names the variable " + tls.passwordEnv()
                        + ", but it is not set.");
            }
            password = value.toCharArray();
        }
        var path = cwd.resolve(tls.keyStore()).normalize();
        try {
            return TlsSupport.fromKeyStore(path, password);
        } catch (IllegalArgumentException e) {
            throw new CliException(e.getMessage());
        }
    }

    /**
     * Turns on the SSH channel. The bridge starts the server and builds the SSH transport. Call it before
     * {@link #start}. A server that cannot start leaves the bridge without SSH.
     */
    public SwarmBridge withSsh(SwarmSshServer server) {
        try {
            server.start();
        } catch (RuntimeException e) {
            server.close();
            log.atWarn().log(() -> "The SSH server did not start: " + e.getMessage());
            return this;
        }
        var transport = new SshChannelTransport(swarmConfig, self.peerId(), server.keys(), swarmConfig.token(),
                this::sshAddressOf);
        server.handler(transport.serverHandler());
        this.sshServer = server;
        this.sshTransport = transport;
        return this;
    }

    /** The SSH address of a peer, from the membership table; blank when it is unknown. */
    private String sshAddressOf(String peerId) {
        var n = node;
        var record = n == null ? null : n.members().get(peerId);
        return record == null ? "" : record.sshAddress();
    }

    /** The other peers in the given state; empty when the node did not start. */
    public List<PeerRecord> peers(PeerState state) {
        var n = node;
        return n == null ? List.of() : n.peers(state);
    }

    /** The swarm token that this bridge uses. It is never empty. */
    String token() { return swarmConfig.token(); }

    /** The peer id of this instance. */
    public String peerId() { return self.peerId(); }

    /** The {@code host:port} address of the SSH server, or blank when SSH is off. */
    public String sshAddress() { return sshServer == null ? "" : sshServer.address(); }

    /** Turns on discovery. Call it before {@link #start}. */
    public SwarmBridge withDiscovery(PeerDiscovery value) {
        this.discovery = value;
        return this;
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
        runtime.registerRoot(HOST_ROOT, "remote", new CancellationToken(), hostTools, policy, this::onRootInput,
                () -> 1);
        var caps = new ArrayList<String>();
        hostTools.forEach(t -> caps.add("tools:" + t.definition().name()));
        capabilities = List.copyOf(caps);
    }

    /** Sets the receiver of messages from peers for the agent of the user. {@link #attach} sets it too. */
    public SwarmBridge mainInput(Consumer<AgentInput> value) {
        this.mainInput = value;
        return this;
    }

    /** Starts the node and joins the swarm. */
    public SwarmBridge start() {
        var started = new SwarmNode(swarmConfig, self.withCapabilities(capabilities), transport, sshTransport, clock,
                this, discovery, sshAddress());
        node = started.start();
        return this;
    }

    public SwarmNode node() { return node; }

    /** The node, or null when the bridge did not start. */
    public String address() { return node == null ? self.address() : node.address(); }

    // ---- messages between agents, over SSH ----

    /**
     * Finds the id of an alive peer from its id, its name, or its address. Returns the argument when no peer
     * matches, so that the caller gets a clear error from the node.
     */
    private String peerIdOf(String peer) {
        var n = node;
        if (n == null || peer == null) return peer;
        return n.peers(PeerState.ALIVE).stream()
                .filter(p -> p.peerId().equals(peer) || p.name().equalsIgnoreCase(peer) || p.address().equals(peer))
                .findFirst().map(PeerRecord::peerId).orElse(peer);
    }

    /** Sends text to an agent on a peer over SSH. The stage fails when the peer cannot be reached. */
    public java.util.concurrent.CompletableFuture<Void> sendMessage(String peerId, String agent, String text) {
        var n = node;
        if (n == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("the swarm is not started"));
        }
        return n.sendAgentMessage(peerIdOf(peerId), agent, text);
    }

    /**
     * Asks a question of an agent on a peer over SSH. The stage completes with the answer. It fails with the
     * reason of the peer, with a timeout, or when the peer is unknown or has no SSH address. It does not throw.
     */
    public java.util.concurrent.CompletableFuture<String> askPeer(String peerId, String agent, String question) {
        var n = node;
        if (n == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("the swarm is not started"));
        }
        return n.ask(peerIdOf(peerId), agent, question, null);
    }

    /** The messenger for the tools {@code send_message} and {@code ask_peer}. */
    public org.github.lscoughlin.jeorje.core.port.PeerMessenger messenger() {
        return new org.github.lscoughlin.jeorje.core.port.PeerMessenger() {
            @Override
            public void message(String peerId, String agent, String text) {
                try {
                    sendMessage(peerId, agent, text).get(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    var cause = e.getCause() == null ? e : e.getCause();
                    throw new IllegalStateException(String.valueOf(cause.getMessage()), cause);
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new IllegalStateException("the peer did not take the message in time", e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
            }

            @Override
            public java.util.concurrent.CompletableFuture<String> ask(String peerId, String agent, String question) {
                return askPeer(peerId, agent, question);
            }

            @Override
            public List<String> peerIds() {
                return peers(PeerState.ALIVE).stream().map(PeerRecord::peerId).toList();
            }
        };
    }

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

            @Override public void answerError(String questionId, String reason) {
                delegation.answerError(questionId, reason);
            }

            @Override public java.util.concurrent.CompletableFuture<String> ask(String questionId, String text) {
                return delegation.ask(questionId, text);
            }

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
        var limit = request.limits();
        // The limits of the delegating peer cap the task. The own limits of this peer still hold.
        var calls = limit == null ? 0 : Math.max(0, limit.maxTurns());
        var time = limit == null || limit.timeoutSeconds() <= 0 ? null
                : java.time.Duration.ofSeconds(limit.timeoutSeconds());
        try {
            id = rt.spawn(HOST_ROOT, spawn, calls, time);
        } catch (SpawnException e) {
            throw new DelegateRejectedException(e.getMessage());
        }
        hosted.put(id, entry);
        notice.accept("Peer " + peerName + " delegated a task: " + shorten(request.task()));
        // The task can end before the entry exists. Send the result now in that case.
        rt.completion(id).ifPresent(completion -> finishHosted(id, completion));
        log.atInfo().log(() -> "accepted task " + request.taskId() + " from " + peerName + " as " + id
                + (limit == null ? "" : " (limits " + limit + ")"));
        return new RunningTask() {
            @Override public void steer(String text) { rt.steer(id, text); }

            @Override public void cancel() { rt.cancel(id.value()); }

            @Override public void answer(String questionId, String text) {
                if (!rt.answer(HOST_ROOT, questionId, text).ok()) {
                    rt.steer(id, "[answer to question " + questionId + " from the delegating peer]\n" + text);
                }
            }

            @Override public void answerError(String questionId, String reason) {
                if (!rt.answerFailure(HOST_ROOT, questionId, reason)) {
                    rt.steer(id, "[the delegating peer refused the question " + questionId + ": " + reason + "]");
                }
            }

            @Override public java.util.concurrent.CompletionStage<String> ask(String questionId, String text) {
                return rt.askFromPeer(HOST_ROOT, id, text);
            }
        };
    }

    private static final java.util.regex.Pattern QUESTION_HEADER =
            java.util.regex.Pattern.compile("\\[question from [^\\n]*?, id (q\\d+)\\]\\n");

    /**
     * The input of the hidden root agent. A hosted subagent sends here when it asks its parent or sends it a
     * message. The bridge forwards a question to the delegating peer as a {@code QUESTION} event, and other text
     * as progress.
     */
    private void onRootInput(AgentInput input) {
        if (!(input instanceof AgentInput.PeerMessage message)) return;
        var entry = hosted.get(message.from());
        if (entry == null || entry.done().get()) return;
        var matcher = QUESTION_HEADER.matcher(message.text());
        if (matcher.find()) {
            entry.sink().accept(DelegateEvent.question(entry.taskId(), matcher.group(1),
                    message.text().substring(matcher.end())));
        } else {
            entry.sink().accept(DelegateEvent.progress(entry.taskId(), message.text()));
        }
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
        var sinks = lineSinks.get(event.agent());
        if (sinks != null && !sinks.isEmpty()) {
            for (var line : agentLines.render(event)) {
                for (var sink : sinks) {
                    try {
                        sink.accept(line);
                    } catch (RuntimeException e) {
                        log.atDebug().log(() -> "a line sink failed: " + e);
                    }
                }
            }
        }
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

    // ---- foreground attach: the host side ----

    /**
     * The access of remote clients to the local agents. The interactive session sets its own access, which uses
     * the chat history and the permission prompts. Without one, the bridge uses the subagent runtime.
     */
    public interface AgentAccess {
        /** The agents that a client can attach to. */
        List<PeerAgent> roster();

        /** The history lines of an agent, oldest first. */
        List<String> history(AgentId agent);

        /** Steering text from a client, as if the user typed it for the agent. */
        void steer(AgentId agent, String text);

        /** The answer of a client to a question of the agent, for example {@code yes} to a permission request. */
        void answer(AgentId agent, String requestId, String text);
    }

    /** Sets the access of remote clients to the local agents. */
    public SwarmBridge agentAccess(AgentAccess value) {
        this.access = value;
        return this;
    }

    private AgentAccess access() {
        var current = access;
        return current != null ? current : runtimeAccess;
    }

    /** The access that needs only the subagent runtime. It has no history and answers nothing. */
    private final AgentAccess runtimeAccess = new AgentAccess() {
        @Override
        public List<PeerAgent> roster() {
            var rt = runtime;
            if (rt == null) return List.of();
            return rt.status().stream().filter(info -> !HOST_ROOT.equals(info.id()))
                    .map(info -> new PeerAgent(info.id().value(), info.name(),
                            info.parent() == null ? "" : info.parent().value(), info.state().name())).toList();
        }

        @Override
        public List<String> history(AgentId agent) { return List.of(); }

        @Override
        public void steer(AgentId agent, String text) {
            var rt = runtime;
            if (rt == null || !rt.steer(agent, text)) {
                throw new IllegalStateException("the agent " + agent + " cannot take steering now");
            }
        }

        @Override
        public void answer(AgentId agent, String requestId, String text) {
            throw new IllegalStateException("this instance cannot take answers from a client");
        }
    };

    private final ConcurrentHashMap<AgentId, java.util.concurrent.CopyOnWriteArrayList<Consumer<String>>> lineSinks =
            new ConcurrentHashMap<>();
    private final AgentLines agentLines = new AgentLines();

    @Override
    public List<PeerAgent> roster() { return access().roster(); }

    @Override
    public ForegroundHost attach(String fromPeer, String agentId) {
        if (agentId == null || agentId.isBlank()) return null;
        var known = roster().stream().anyMatch(a -> a.agentId().equals(agentId));
        if (!known) return null;
        var id = new AgentId(agentId);
        var name = nameOf(fromPeer);
        log.atInfo().log(() -> "peer " + name + " attached to " + agentId);
        notice.accept("Peer " + name + " attached to the agent " + agentId + ".");
        return new ForegroundHost() {
            private volatile Consumer<String> mine;

            @Override public List<String> history() { return access().history(id); }

            @Override public String agentId() { return agentId; }

            @Override public void steer(String text) { access().steer(id, text); }

            @Override public void answer(String requestId, String text) { access().answer(id, requestId, text); }

            @Override
            public void onLine(Consumer<String> sink) {
                var old = mine;
                if (old != null) {
                    var list = lineSinks.get(id);
                    if (list != null) list.remove(old);
                }
                mine = sink;
                if (sink != null) lineSinks.computeIfAbsent(id, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                        .add(sink);
            }

            @Override
            public void detach() {
                onLine(null);
                notice.accept("Peer " + name + " detached from the agent " + agentId + ".");
            }
        };
    }

    /** Answers a question from an agent on a peer. */
    @FunctionalInterface
    public interface PeerAnswerer {
        java.util.concurrent.CompletionStage<String> answer(String fromPeerName, String toAgent, String question);
    }

    /**
     * Sets the code that answers questions from peers. Without it, the question goes to the agent of the user as
     * a message, and the asking peer gets an error that says so. The agent can reply with a message.
     */
    public SwarmBridge answerPeersWith(PeerAnswerer value) {
        this.answerer = value;
        return this;
    }

    @Override
    public java.util.concurrent.CompletionStage<String> onAgentAsk(String fromPeer, String toAgent, String question) {
        var name = nameOf(fromPeer);
        var current = answerer;
        if (current != null) return current.answer(name, toAgent, question);
        mainInput.accept(new AgentInput.PeerMessage(new AgentId("peer:" + name), "[question] " + question));
        return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException(
                "the agent received the question as a message and has no direct answer; it can reply with send_message"));
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
            if (sshTransport != null) sshTransport.close();
        }
        var server = sshServer;
        if (server != null) server.close();
        var disco = discovery;
        if (n == null && disco != null) disco.withdraw(self.peerId());
    }
}
