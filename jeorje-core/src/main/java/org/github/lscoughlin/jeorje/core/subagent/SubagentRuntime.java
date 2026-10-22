package org.github.lscoughlin.jeorje.core.subagent;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import org.github.lscoughlin.jeorje.core.agent.Agent;
import org.github.lscoughlin.jeorje.core.agent.AgentInput;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.agent.TranscriptSink;
import org.github.lscoughlin.jeorje.core.instructions.AgentTypeRegistry;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The runtime of the subagents of one instance. It spawns subagents, tracks their life cycle, enforces the
 * limits, makes the completion summary, delivers it, and carries the messages and questions between agents.
 *
 * <p>The runtime keeps one lock for its tables. It does not call out of the runtime while it holds the lock.
 */
public final class SubagentRuntime implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SubagentRuntime.class);

    /** The {@code kind} of the history chunk of a completion summary. */
    public static final String SUMMARY_KIND = "subagent_summary";
    /** The name of the tool that a subagent gets only when it asks for it. */
    public static final String SPAWN_TOOL = "spawn_agent";
    /** The first part number of summary chunks. It is above the parts that a turn makes. */
    static final int SUMMARY_PART_BASE = 1000;

    /** Gives the model client for a model slug. A null slug means the default model. */
    @FunctionalInterface
    public interface LlmProvider {
        LlmClient forModel(String model);
    }

    private final String sessionId;
    private final AgentSettings settings;
    private final LlmProvider llm;
    private final LlmClient compactor;
    private final ContextStore store;
    private final TranscriptSink transcript;
    private final Consumer<AgentEvent> agentEvents;
    private final java.util.function.BiConsumer<AgentId, List<Chunk>> pruneListener;
    private final Consumer<SubagentEvent> events;
    private final Clock clock;
    private final AgentTypeRegistry types;
    private final SubagentLimits limits;
    private final CompletionSteps steps;
    private final RemoteSpawner remote;
    private final WorkspacePolicies workspacePolicies;

    private final Object lock = new Object();
    private volatile ExternalDelivery externalDelivery;
    private final Map<AgentId, AgentNode> nodes = new LinkedHashMap<>();
    private final Map<String, Question> questions = new LinkedHashMap<>();
    private final Set<String> pending = new LinkedHashSet<>();
    private final List<Tool> sharedTools = new ArrayList<>();
    private final AtomicInteger agentCounter = new AtomicInteger();
    private final AtomicInteger questionCounter = new AtomicInteger();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "subagent-watchdog");
        t.setDaemon(true);
        return t;
    });

    private SubagentRuntime(Builder b) {
        this.sessionId = b.sessionId;
        this.settings = b.settings;
        this.llm = b.llm;
        this.compactor = b.compactor;
        this.store = b.store;
        this.transcript = b.transcript == null ? TranscriptSink.none() : b.transcript;
        this.agentEvents = b.agentEvents == null ? e -> { } : b.agentEvents;
        this.pruneListener = b.pruneListener;
        this.events = b.events == null ? e -> { } : b.events;
        this.clock = b.clock == null ? Clock.systemUTC() : b.clock;
        this.types = b.types;
        this.limits = b.limits == null ? SubagentLimits.defaults() : b.limits;
        this.steps = b.steps == null ? CompletionSteps.none() : b.steps;
        this.remote = b.remote;
        this.workspacePolicies = b.workspacePolicies;
    }

    public static Builder builder(String sessionId, AgentSettings settings, LlmProvider llm) {
        return new Builder(sessionId, settings, llm);
    }

    /** Builds a {@link SubagentRuntime}. */
    public static final class Builder {
        private final String sessionId;
        private final AgentSettings settings;
        private final LlmProvider llm;
        private LlmClient compactor;
        private ContextStore store;
        private TranscriptSink transcript;
        private Consumer<AgentEvent> agentEvents;
        private java.util.function.BiConsumer<AgentId, List<Chunk>> pruneListener;
        private Consumer<SubagentEvent> events;
        private Clock clock;
        private AgentTypeRegistry types;
        private SubagentLimits limits;
        private CompletionSteps steps;
        private RemoteSpawner remote;
        private WorkspacePolicies workspacePolicies;

        private Builder(String sessionId, AgentSettings settings, LlmProvider llm) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.settings = Objects.requireNonNull(settings, "settings");
            this.llm = Objects.requireNonNull(llm, "llm");
        }

        /** The compactor model, used to build the summary of a failed or cancelled subagent. Null means none. */
        public Builder compactor(LlmClient value) { this.compactor = value; return this; }

        /** The full session index. All agents share it, so {@code recall} sees the chunks of all agents. */
        public Builder store(ContextStore value) { this.store = value; return this; }

        public Builder transcript(TranscriptSink value) { this.transcript = value; return this; }

        /** Receives the chunks that compaction newly pruned in a subagent, with the id of the subagent. */
        public Builder pruneListener(java.util.function.BiConsumer<AgentId, List<Chunk>> value) {
            this.pruneListener = value;
            return this;
        }

        /** Receives the events of all subagent loops. */
        public Builder agentEvents(Consumer<AgentEvent> value) { this.agentEvents = value; return this; }

        public Builder events(Consumer<SubagentEvent> value) { this.events = value; return this; }

        public Builder clock(Clock value) { this.clock = value; return this; }

        public Builder types(AgentTypeRegistry value) { this.types = value; return this; }

        public Builder limits(SubagentLimits value) { this.limits = value; return this; }

        public Builder steps(CompletionSteps value) { this.steps = value; return this; }

        /**
         * Makes the policy of a subagent with isolation {@code worktree}, so that its workspace is its own
         * directory. Without it, such a subagent uses the policy of its parent.
         */
        public Builder workspacePolicies(WorkspacePolicies value) { this.workspacePolicies = value; return this; }

        /** The port to the swarm. Without it, a spawn with a peer fails. */
        public Builder remote(RemoteSpawner value) { this.remote = value; return this; }

        public SubagentRuntime build() { return new SubagentRuntime(this); }
    }

    public SubagentLimits limits() { return limits; }

    // ---- registration ----

    /**
     * Registers an agent that the runtime does not run, for example the agent of the user. Its tools are the base
     * of the tools of its subagents.
     *
     * @param input receives subagent results and messages for this agent
     * @param currentTurn gives the number of the current or next turn of the agent, for the history chunks
     */
    public AgentId registerRoot(AgentId id, String name, CancellationToken token, List<Tool> tools,
                                PermissionPolicy policy, Consumer<AgentInput> input, IntSupplier currentTurn) {
        var node = new AgentNode(id, name, null, null, token, tools, policy, input, currentTurn, Set.of(), true);
        synchronized (lock) {
            if (nodes.putIfAbsent(id, node) != null) throw new IllegalArgumentException("agent exists: " + id);
        }
        return id;
    }

    /** Sets the sink that gets a message or a question for an agent that this runtime does not own. */
    public void setExternalDelivery(ExternalDelivery external) {
        this.externalDelivery = external;
    }

    /** Adds tools that every subagent gets, for example {@code recall} and the agent tools. */
    public void addSharedTools(List<Tool> tools) {
        synchronized (lock) {
            for (var tool : tools) {
                var name = tool.definition().name();
                sharedTools.removeIf(t -> t.definition().name().equals(name));
                sharedTools.add(tool);
            }
        }
    }

    /** Marks a key (for example a story id) as an agent that exists but did not start. */
    public void registerPending(String key) {
        synchronized (lock) { pending.add(key); }
    }

    public void clearPending(String key) {
        synchronized (lock) { pending.remove(key); }
    }

    // ---- spawn ----

    /** Starts a subagent for the parent agent. */
    public AgentId spawn(AgentId parentId, SpawnRequest request) {
        return spawn(parentId, request, 0, null);
    }

    /**
     * Starts a subagent with caps from a delegating peer. The task runs under the smaller of the own limit and the
     * cap. A cap of 0 (calls) or null (time) means no cap.
     */
    public AgentId spawn(AgentId parentId, SpawnRequest request, int maxModelCallsCap, Duration maxDurationCap) {
        if (request.remote()) return spawnRemote(parentId, request, maxModelCallsCap, maxDurationCap);
        if (request.name() == null || request.name().isBlank()) throw new SpawnException("The name is empty.");
        if (request.task() == null || request.task().isBlank()) throw new SpawnException("The task is empty.");
        var type = request.type() == null ? null : Optional.ofNullable(types)
            .flatMap(t -> t.find(request.type())).orElseThrow(() -> new SpawnException(
                "Unknown agent type '" + request.type() + "'."));
        var isolation = request.isolation() != null ? request.isolation() : type == null ? null : type.isolation();
        if (isolation != null && !isolation.equals("worktree") && !isolation.equals("shared")) {
            throw new SpawnException("Unknown isolation '" + isolation + "'. Use worktree or shared.");
        }
        var model = request.model() != null ? request.model() : type == null ? null : type.model();
        var effective = request.withIsolation(isolation);
        AgentNode node;
        synchronized (lock) {
            var parent = nodes.get(parentId);
            if (parent == null) throw new SpawnException("Unknown parent agent " + parentId + ".");
            if (parent.ended()) throw new SpawnException("The parent agent has ended.");
            if (parent.depth + 1 > limits.maxDepth()) {
                throw new SpawnException("Depth limit: a subagent can nest at most " + limits.maxDepth() + " levels.");
            }
            var running = nodes.values().stream().filter(n -> !n.root && !n.ended()).count();
            if (running >= limits.maxConcurrent()) {
                throw new SpawnException("Limit: " + limits.maxConcurrent() + " subagents run at the same time.");
            }
            if (nodes.values().stream().anyMatch(n -> !n.ended() && n.name.equalsIgnoreCase(request.name()))) {
                throw new SpawnException("An agent with the name '" + request.name() + "' is running.");
            }
            var requested = request.tools() != null ? request.tools()
                : type != null && type.tools() != null ? type.tools() : null;
            var available = new LinkedHashMap<String, Tool>();
            parent.tools.forEach(t -> available.put(t.definition().name(), t));
            var chosen = new LinkedHashMap<String, Tool>();
            var dropped = new LinkedHashSet<String>();
            if (requested == null) {
                available.forEach((k, v) -> {
                    if (!k.equals(SPAWN_TOOL)) chosen.put(k, v);
                });
            } else {
                for (var name : requested) {
                    if (available.containsKey(name)) chosen.put(name, available.get(name));
                    else if (sharedTools.stream().noneMatch(t -> t.definition().name().equals(name))) dropped.add(name);
                }
            }
            for (var tool : sharedTools) chosen.putIfAbsent(tool.definition().name(), tool);
            var id = new AgentId("sub-" + agentCounter.incrementAndGet());
            var basePolicy = parent.policy;
            if (workspacePolicies != null && request.workdir() != null && "worktree".equals(isolation)) {
                basePolicy = workspacePolicies.forWorkspace(parent.policy, id, request.workdir());
            }
            var policy = PolicyNarrowing.narrow(basePolicy, null, Set.copyOf(chosen.keySet()));
            node = new AgentNode(id, request.name(), parent, effective, new CancellationToken(),
                List.copyOf(chosen.values()), policy, null, null, Set.copyOf(dropped), false);
            node.callCap = Math.max(0, maxModelCallsCap);
            node.timeCap = maxDurationCap;
            var self = node;
            node.input = in -> deliver(self, new AgentNode.InboxItem(in.text(), null));
            nodes.put(id, node);
            parent.children.add(node);
            if (request.mode() == SpawnMode.WAIT) {
                parent.internalWaits.add(new AgentNode.InternalWait(id, "waiting for subagent " + request.name()));
            }
        }
        node.parent.token.onCancel(node.token::cancel);
        emit(new SubagentEvent.Spawned(node.id, node.parent.id, node.name, node.depth));
        var typeBody = type == null ? "" : type.body();
        var started = node;
        Thread.ofVirtual().name("subagent-" + node.id).start(() -> run(started, model, typeBody));
        return node.id;
    }


    // ---- remote subagents ----

    private AgentId spawnRemote(AgentId parentId, SpawnRequest request, int callCap, Duration timeCap) {
        if (request.name() == null || request.name().isBlank()) throw new SpawnException("The name is empty.");
        if (request.task() == null || request.task().isBlank()) throw new SpawnException("The task is empty.");
        if (remote == null) {
            throw new SpawnException("The swarm is off. Set swarm.enabled or start with --peer host:port.");
        }
        var peer = remote.resolve(request.peer(), request.capability()).orElseThrow(() -> new SpawnException(
            request.peer() != null ? "No live peer matches '" + request.peer() + "'."
                : "No live peer has the capability '" + request.capability() + "'."));
        AgentNode node;
        synchronized (lock) {
            var parent = nodes.get(parentId);
            if (parent == null) throw new SpawnException("Unknown parent agent " + parentId + ".");
            if (parent.ended()) throw new SpawnException("The parent agent has ended.");
            if (parent.depth + 1 > limits.maxDepth()) {
                throw new SpawnException("Depth limit: a subagent can nest at most " + limits.maxDepth() + " levels.");
            }
            var running = nodes.values().stream().filter(x -> !x.root && !x.ended()).count();
            if (running >= limits.maxConcurrent()) {
                throw new SpawnException("Limit: " + limits.maxConcurrent() + " subagents run at the same time.");
            }
            if (nodes.values().stream().anyMatch(x -> !x.ended() && x.name.equalsIgnoreCase(request.name()))) {
                throw new SpawnException("An agent with the name '" + request.name() + "' is running.");
            }
            var id = new AgentId("sub-" + agentCounter.incrementAndGet());
            node = new AgentNode(id, request.name(), parent, request, new CancellationToken(), List.of(),
                parent.policy, null, null, Set.of(), false);
            node.remotePeer = peer.name();
            var self = node;
            node.input = in -> deliver(self, new AgentNode.InboxItem(in.text(), null));
            nodes.put(id, node);
            parent.children.add(node);
            if (request.mode() == SpawnMode.WAIT) {
                parent.internalWaits.add(new AgentNode.InternalWait(id, "waiting for subagent " + request.name()));
            }
        }
        node.parent.token.onCancel(node.token::cancel);
        emit(new SubagentEvent.Spawned(node.id, node.parent.id, node.name, node.depth));
        transition(node, AgentLifecycle.RUNNING, "running on peer " + peer.name());
        var started = node;
        var tools = request.tools() == null ? List.<String>of() : request.tools();
        try {
            var calls = callCap > 0 ? Math.min(limits.maxModelCalls(), callCap) : limits.maxModelCalls();
            var duration = timeCap != null && timeCap.compareTo(limits.maxDuration()) < 0 ? timeCap
                : limits.maxDuration();
            var task = remote.start(peer, request.task(), tools, calls,
                (int) Math.min(Integer.MAX_VALUE, duration.toSeconds()), event -> onRemoteEvent(started, event));
            started.remote = task;
            started.remoteTask.complete(task);
            task.completion().whenComplete((last, error) -> {
                if (error != null) {
                    var cause = error.getCause() == null ? error : error.getCause();
                    endRemote(started, Completion.Outcome.FAILED, "Lost the peer " + peer.name() + ": "
                        + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()), null);
                }
            });
            started.token.onCancel(task::cancel);
            var maxDuration = duration;
            scheduler.schedule(() -> {
                if (started.ended()) return;
                started.token.cancel();
                endRemote(started, Completion.Outcome.FAILED, "Time limit reached: " + maxDuration.toMillis() + " ms.",
                    null);
            }, maxDuration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            endRemote(started, Completion.Outcome.FAILED, "Cannot start the task on the peer: "
                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), null);
        }
        return node.id;
    }

    private void onRemoteEvent(AgentNode n, RemoteSpawner.Event event) {
        if (event.last()) {
            var outcome = switch (event.outcome() == null ? "" : event.outcome()) {
                case "completed" -> Completion.Outcome.COMPLETED;
                case "cancelled" -> Completion.Outcome.CANCELLED;
                default -> Completion.Outcome.FAILED;
            };
            var text = event.summary() == null || event.summary().isBlank() ? event.text() : event.summary();
            var reason = outcome == Completion.Outcome.COMPLETED ? null
                : outcome == Completion.Outcome.CANCELLED ? "cancelled"
                : event.text() == null || event.text().isBlank() ? event.outcome() : event.text();
            endRemote(n, outcome, reason, text == null ? "" : text, event.text());
            return;
        }
        var prefix = event.kind() == RemoteSpawner.Kind.QUESTION ? "[question from the remote agent, id "
            + event.questionId() + "] " : "";
        var line = prefix + (event.text() == null ? "" : event.text()) + "\n";
        trackOutput(n, line);
        if (event.kind() == RemoteSpawner.Kind.QUESTION && event.questionId() != null) remoteQuestion(n, event);
        try {
            agentEvents.accept(new AgentEvent.TokenDelta(n.id, line));
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "An event consumer failed: " + e.getMessage());
        }
    }

    /**
     * A question of a remote subagent for its parent. The parent gets it like a question of a local subagent, with
     * the same rules (limit, open ask, deadlock, busy, timeout). The answer of the parent goes back to the peer.
     */
    private void remoteQuestion(AgentNode n, RemoteSpawner.Event event) {
        var remoteId = event.questionId();
        var text = event.text() == null ? "" : event.text();
        var parent = n.parent;
        String refusal = null;
        Question q = null;
        synchronized (lock) {
            if (n.ended() || parent.ended()) refusal = AskResult.ENDED;
            else if (n.asks >= limits.maxAsks()) refusal = AskResult.LIMIT;
            else if (n.openAsk != null && n.openAsk.open()) refusal = AskResult.OPEN_ASK;
            else if (reaches(parent, n)) refusal = AskResult.DEADLOCK;
            else if (!parent.internalWaits.isEmpty()) refusal = AskResult.BUSY;
            else {
                q = new Question("q" + questionCounter.incrementAndGet(), n.id, parent.id, text);
                n.openAsk = q;
                n.asks++;
                questions.put(q.id, q);
            }
        }
        if (refusal != null) {
            var reason = refusal;
            n.remoteTask.thenAccept(task -> task.answerError(remoteId, reason));
            return;
        }
        var question = q;
        var timeout = scheduler.schedule(() -> question.reply.complete(AskResult.failure(AskResult.TIMEOUT)),
            Math.max(1, limits.askTimeout().toMillis()), TimeUnit.MILLISECONDS);
        question.reply.whenComplete((result, error) -> {
            timeout.cancel(false);
            synchronized (lock) {
                if (n.openAsk == question) n.openAsk = null;
                questions.remove(question.id);
                parent.inbox.removeIf(item -> item.question() == question);
            }
            n.remoteTask.thenAccept(task -> {
                try {
                    if (result != null && result.ok()) task.answer(remoteId, result.answer());
                    else task.answerError(remoteId, result == null ? AskResult.CANCELLED : result.reason());
                } catch (RuntimeException e) {
                    log.atDebug().log(() -> "Cannot send the answer to the peer: " + e.getMessage());
                }
            });
        });
        var body = "[question from " + n.name + " (" + label(n) + "), id " + question.id + "]\n" + text;
        if (!send(n, parent, body, question)) question.reply.complete(AskResult.ended(null));
    }

    private void endRemote(AgentNode n, Completion.Outcome outcome, String reason, String finalText) {
        endRemote(n, outcome, reason, null, finalText);
    }

    /** Ends a remote subagent once. A second call does nothing. */
    private void endRemote(AgentNode n, Completion.Outcome outcome, String reason, String remoteSummary,
                           String finalText) {
        synchronized (lock) {
            if (n.ending || n.state.terminal()) return;
            n.ending = true;
        }
        var summary = remoteSummary != null && !remoteSummary.isBlank() ? remoteSummary
            : "Outcome: " + outcome.name().toLowerCase(Locale.ROOT) + ". Task \"" + n.name + "\" on the peer "
                + n.remotePeer + (reason == null ? "." : ": " + reason);
        var labeled = "Remote subagent on the peer " + n.remotePeer + ".\n" + summary;
        switch (outcome) {
            case COMPLETED -> transition(n, AgentLifecycle.COMPLETED, null);
            case FAILED -> transition(n, AgentLifecycle.FAILED, reason);
            case CANCELLED -> transition(n, AgentLifecycle.CANCELLED, reason);
        }
        conclude(n, outcome, reason, labeled, finalText == null ? summary : finalText);
    }

    // ---- the loop of one subagent ----

    private void run(AgentNode n, String model, String typeBody) {
        ScheduledFuture<?> watchdog = null;
        LlmClient plain = null;
        try {
            plain = llm.forModel(model);
            var maxCalls = n.callCap > 0 ? Math.min(limits.maxModelCalls(), n.callCap) : limits.maxModelCalls();
            var limited = new LimitedLlm(plain, maxCalls,
                () -> fail(n, "Model call limit reached: " + maxCalls + " calls."));
            var workdir = n.request.workdir() != null ? n.request.workdir() : settings.workingDirectory();
            var suffix = new StringBuilder();
            suffix.append("You are the subagent \"").append(n.name).append("\" of the agent \"")
                .append(n.parent.name).append("\".");
            if (!typeBody.isBlank()) suffix.append('\n').append(typeBody);
            suffix.append("\nYour task:\n").append(n.request.task());
            var base = settings.instructions();
            var fixed = base.isEmpty() ? suffix.toString() : base + "\n\n" + suffix;
            var childSettings = new AgentSettings(sessionId, workdir, settings.contextWindowTokens(),
                settings.budgetPercent(), settings.maxOutputTokens(), settings.recentTurns(),
                maxCalls, settings.retry(), settings.systemPrompt(), fixed,
                settings.sleeper(), null, settings.maxProtectedTokens(), settings.recentMaxPercent(),
                settings.repeatGuard());
            if (settings.hasInstructionsSource()) {
                childSettings = childSettings.withInstructionsSource(dir -> {
                    var parentText = settings.instructionsFor(dir);
                    return parentText.isEmpty() ? suffix.toString() : parentText + "\n\n" + suffix;
                });
            }
            var agent = new Agent(n.id, childSettings, limited, n.tools, n.policy, store, transcript,
                event -> onAgentEvent(n, event), clock);
            agent.withStepBoundary(() -> {
                var pending = takeInbox(n);
                return pending.isEmpty() ? List.of() : List.of(pending);
            });
            if (pruneListener != null) agent.withPruneListener(chunks -> pruneListener.accept(n.id, chunks));
            n.agent = agent;
            n.turn = () -> agent.turns().size() + 1;
            n.token.onCancel(agent::cancel);
            var maxDuration = n.timeCap != null && n.timeCap.compareTo(limits.maxDuration()) < 0 ? n.timeCap
                : limits.maxDuration();
            watchdog = scheduler.schedule(() -> fail(n, "Time limit reached: " + maxDuration.toMillis() + " ms."),
                maxDuration.toMillis(), TimeUnit.MILLISECONDS);
            transition(n, AgentLifecycle.RUNNING, null);
            var next = n.request.task();
            while (!n.token.isCancelled()) {
                agent.runTurn(next);
                if (n.token.isCancelled()) break;
                next = endOrTakeInbox(n);
                if (next == null) break;
            }
        } catch (Throwable t) {
            log.atWarn().setCause(t).log(() -> "Subagent " + n.name + " failed");
            fail(n, "Internal error: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        } finally {
            if (watchdog != null) watchdog.cancel(false);
        }
        try {
            finish(n, plain);
        } catch (Throwable t) {
            log.atError().setCause(t).log(() -> "Cannot finish subagent " + n.name);
            n.result.completeExceptionally(t);
        }
    }

    private String endOrTakeInbox(AgentNode n) {
        synchronized (lock) {
            var text = drain(n);
            return text.isEmpty() ? null : text;
        }
    }

    private void fail(AgentNode n, String reason) {
        synchronized (lock) {
            if (n.failureReason != null || n.state.terminal()) return;
            n.failureReason = reason;
        }
        n.token.cancel();
    }

    private void onAgentEvent(AgentNode n, AgentEvent event) {
        switch (event) {
            case AgentEvent.TokenDelta d -> trackOutput(n, d.text());
            case AgentEvent.ConfirmationRequested c -> enterWait(n, AgentLifecycle.WAITING_INPUT, "user");
            case AgentEvent.ToolStarted s when s.tool().equals("ask_user") ->
                enterWait(n, AgentLifecycle.WAITING_INPUT, "user");
            case AgentEvent.ToolStarted s -> leaveWait(n, AgentLifecycle.WAITING_INPUT);
            case AgentEvent.ToolFinished f -> leaveWait(n, AgentLifecycle.WAITING_INPUT);
            case AgentEvent.Error e when !e.message().startsWith("Cannot store turn") -> fail(n, e.message());
            default -> { }
        }
        try {
            agentEvents.accept(event);
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "An event consumer failed: " + e.getMessage());
        }
    }

    private static void trackOutput(AgentNode n, String delta) {
        synchronized (n.tail) {
            n.tail.append(delta);
            if (n.tail.length() > 2_000) n.tail.delete(0, n.tail.length() - 2_000);
            var text = n.tail.toString().stripTrailing();
            var nl = text.lastIndexOf('\n');
            var line = (nl >= 0 ? text.substring(nl + 1) : text).strip();
            n.lastOutput = line.length() > 200 ? line.substring(0, 200) : line;
        }
    }

    // ---- finish ----

    private void finish(AgentNode n, LlmClient plain) {
        var outcome = n.failureReason != null ? Completion.Outcome.FAILED
            : n.token.isCancelled() ? Completion.Outcome.CANCELLED : Completion.Outcome.COMPLETED;
        var reason = outcome == Completion.Outcome.FAILED ? n.failureReason
            : outcome == Completion.Outcome.CANCELLED ? "cancelled" : null;
        String branch = null;
        if (outcome == Completion.Outcome.COMPLETED) {
            try {
                var result = steps.run(new StepContext(n));
                branch = result == null ? null : result.branch();
                if (result != null && !result.success()) {
                    outcome = Completion.Outcome.FAILED;
                    reason = result.failure() == null ? "The completion steps failed." : result.failure();
                }
            } catch (Throwable t) {
                outcome = Completion.Outcome.FAILED;
                reason = "The completion steps failed: "
                    + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                log.atWarn().setCause(t).log(() -> "Completion steps of " + n.name + " failed");
            }
            if (n.token.isCancelled() && outcome == Completion.Outcome.COMPLETED) {
                outcome = n.failureReason != null ? Completion.Outcome.FAILED : Completion.Outcome.CANCELLED;
                reason = n.failureReason != null ? n.failureReason : "cancelled";
            }
        }
        if (n.branch == null) n.branch = branch;
        synchronized (lock) { n.ending = true; }
        if (outcome != Completion.Outcome.COMPLETED) {
            transition(n, outcome == Completion.Outcome.FAILED ? AgentLifecycle.FAILED : AgentLifecycle.CANCELLED,
                reason);
        }
        var agent = n.agent;
        var history = HistoryText.of(agent == null ? List.of() : agent.turns());
        var label = outcome.name().toLowerCase(Locale.ROOT);
        var writerClient = outcome == Completion.Outcome.COMPLETED ? plain : compactor;
        var request = new SummaryWriter.Request(n.name, label, reason, branch, history);
        String summary;
        try {
            summary = new SummaryWriter(writerClient).write(request);
        } catch (RuntimeException e) {
            summary = SummaryWriter.fallback(request);
        }
        if (outcome == Completion.Outcome.COMPLETED) transition(n, AgentLifecycle.COMPLETED, null);
        conclude(n, outcome, reason, summary, history.lastAssistantText());
    }

    /** Publishes the end of an agent: the summary chunk, the result, the events, and the delivery to the parent. */
    private void conclude(AgentNode n, Completion.Outcome outcome, String reason, String summary, String finalText) {
        var completion = new Completion(n.id, n.name, outcome, reason, summary, finalText);
        var affected = new ArrayList<Question>();
        synchronized (lock) {
            for (var q : questions.values()) if (q.to.equals(n.id) && q.open()) affected.add(q);
            if (n.openAsk != null && n.openAsk.open()) affected.add(n.openAsk);
            n.parent.internalWaits.removeIf(w -> w.holder() != null && w.holder().equals(n.id));
            n.internalWaits.clear();
        }
        writeSummaryChunk(n, summary);
        n.result.complete(completion);
        for (var q : affected) {
            q.reply.complete(q.from.equals(n.id) ? AskResult.failure(AskResult.CANCELLED) : AskResult.ended(summary));
        }
        emit(new SubagentEvent.Finished(n.id, n.name, completion));
        if (n.request.mode() == SpawnMode.BACKGROUND && !n.parent.ended()) {
            try {
                n.parent.input.accept(new AgentInput.SubagentResult(n.id, summary));
            } catch (RuntimeException e) {
                log.atWarn().log(() -> "Cannot deliver the result of " + n.name + ": " + e.getMessage());
            }
        }
    }

    private void writeSummaryChunk(AgentNode child, String summary) {
        if (store == null) return;
        var parent = child.parent;
        int part;
        synchronized (lock) { part = SUMMARY_PART_BASE + parent.summaries++; }
        var turn = parent.turn == null ? 1 : parent.turn.getAsInt();
        var text = "subagent: " + child.name + "\n" + summary;
        try {
            store.append(List.of(new Chunk(parent.id + ":" + turn + "." + part, sessionId, parent.id.value(), turn,
                part, clock.instant(), SUMMARY_KIND, null, text, child.id.value())));
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "Cannot store the summary of " + child.name + ": " + e.getMessage());
        }
    }

    /** The context that the runtime gives to the completion steps. */
    private final class StepContext implements CompletionSteps.Context {
        private final AgentNode n;

        StepContext(AgentNode n) { this.n = n; }

        @Override public AgentId agent() { return n.id; }

        @Override public String name() { return n.name; }

        @Override public Path workingDirectory() {
            return n.request.workdir() != null ? n.request.workdir() : settings.workingDirectory();
        }

        @Override public String isolation() { return n.request.isolation(); }

        @Override public CancellationToken cancel() { return n.token; }

        @Override public void enter(AgentLifecycle phase) {
            if (!phase.completionPhase()) throw new IllegalArgumentException("not a completion phase: " + phase);
            if (!transition(n, phase, null)) {
                log.atDebug().log(() -> "Transition of " + n.name + " to " + phase + " refused from " + n.state);
            }
        }

        @Override public AutoCloseable internalWait(AgentId holder, String reason) {
            return beginInternalWait(n.id, holder, reason);
        }

        @Override public void resumeWork(String message) {
            var agent = n.agent;
            if (agent != null && !n.token.isCancelled()) agent.runTurn(message);
        }

        @Override public int resumeWork(String message, int maxModelCalls) {
            var agent = n.agent;
            if (agent == null || n.token.isCancelled()) return 0;
            return agent.runTurn(message, maxModelCalls);
        }
    }

    // ---- state ----

    private boolean transition(AgentNode n, AgentLifecycle to, String reason) {
        AgentLifecycle from;
        synchronized (lock) {
            from = n.state;
            if (!from.canGo(to)) return false;
            n.state = to;
            if (to == AgentLifecycle.WAITING_INPUT || to == AgentLifecycle.WAITING_AGENT) {
                n.resumeState = from;
            } else {
                n.waitingFor = null;
            }
        }
        emit(new SubagentEvent.StateChanged(n.id, n.name, from, to, reason));
        return true;
    }

    private void enterWait(AgentNode n, AgentLifecycle wait, String target) {
        if (transition(n, wait, "waiting for " + target)) n.waitingFor = target;
    }

    private void leaveWait(AgentNode n, AgentLifecycle wait) {
        AgentLifecycle back;
        synchronized (lock) {
            if (n.state != wait) return;
            back = n.resumeState;
        }
        transition(n, back, null);
    }

    private void emit(SubagentEvent event) {
        try {
            events.accept(event);
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "An event consumer failed: " + e.getMessage());
        }
    }

    // ---- inbox ----

    private boolean deliver(AgentNode target, AgentNode.InboxItem item) {
        synchronized (lock) {
            if (target.ending || target.state.terminal()) return false;
            target.inbox.add(item);
            return true;
        }
    }

    /** Takes the text of the inbox. Questions that are no longer open are dropped. Must hold the lock. */
    private String drain(AgentNode n) {
        var sb = new StringBuilder();
        for (var item : n.inbox) {
            if (item.question() != null && !item.question().open()) continue;
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(item.text());
        }
        n.inbox.clear();
        return sb.toString();
    }

    private String takeInbox(AgentNode n) {
        synchronized (lock) { return drain(n); }
    }

    /** Sends user text to an agent. It is steering. Returns false if the agent ended. */
    public boolean steer(AgentId id, String text) {
        AgentNode n;
        synchronized (lock) { n = nodes.get(id); }
        if (n == null) return false;
        if (n.root) {
            n.input.accept(new AgentInput.UserInput(text));
            return true;
        }
        if (n.remote != null) return steerRemote(n, text);
        return deliver(n, new AgentNode.InboxItem("[message from the user]\n" + text, null));
    }

    // ---- lookups ----

    /** The snapshots of all agents of the session. */
    public List<AgentInfo> status() {
        synchronized (lock) { return nodes.values().stream().map(AgentNode::info).toList(); }
    }

    public Optional<AgentInfo> info(AgentId id) {
        synchronized (lock) { return Optional.ofNullable(nodes.get(id)).map(AgentNode::info); }
    }

    /** The name of an agent for an agent id, or the id when the agent is unknown. */
    public String nameOf(String agentId) {
        synchronized (lock) {
            var node = nodes.get(new AgentId(agentId));
            return node == null ? agentId : node.name;
        }
    }

    /** The agent id for a name, an id, or a story id. A running agent has precedence over an ended one. */
    public Optional<AgentId> find(String key) {
        synchronized (lock) { return Optional.ofNullable(resolve(key)).map(n -> n.id); }
    }

    /** The end result of a subagent, when it has ended. */
    public Optional<Completion> completion(AgentId id) {
        AgentNode n;
        synchronized (lock) { n = nodes.get(id); }
        if (n == null || !n.result.isDone() || n.result.isCompletedExceptionally()) return Optional.empty();
        return Optional.of(n.result.join());
    }

    /** The names of the tools that the agent has. */
    public List<String> toolNames(AgentId id) {
        synchronized (lock) {
            var n = nodes.get(id);
            return n == null ? List.of() : n.tools.stream().map(t -> t.definition().name()).toList();
        }
    }

    /** The names of requested tools that the parent does not have. */
    public Set<String> droppedTools(AgentId id) {
        synchronized (lock) {
            var n = nodes.get(id);
            return n == null ? Set.of() : n.droppedTools;
        }
    }

    /** The policy of an agent. */
    public Optional<PermissionPolicy> policyOf(AgentId id) {
        synchronized (lock) { return Optional.ofNullable(nodes.get(id)).map(n -> n.policy); }
    }

    /** The token of an agent. */
    public Optional<CancellationToken> tokenOf(AgentId id) {
        synchronized (lock) { return Optional.ofNullable(nodes.get(id)).map(n -> n.token); }
    }

    /** Sets the branch that {@code agent_status} shows. The worktree story calls it. */
    public void setBranch(AgentId id, String branch) {
        synchronized (lock) {
            var n = nodes.get(id);
            if (n != null) n.branch = branch;
        }
    }

    private AgentNode resolve(String key) {
        if (key == null) return null;
        AgentNode ended = null;
        for (var n : nodes.values()) {
            var match = n.id.value().equals(key) || n.name.equalsIgnoreCase(key) || key.equals(n.storyId());
            if (!match) continue;
            if (!n.ended()) return n;
            ended = n;
        }
        return ended;
    }

    // ---- results and cancel ----

    /**
     * Waits for the end of a subagent. If the token is cancelled while it waits, the subagent is cancelled and the
     * call returns the result of the cancelled subagent.
     */
    public Completion await(AgentId id, CancellationToken cancel) throws InterruptedException {
        AgentNode n;
        synchronized (lock) { n = nodes.get(id); }
        if (n == null) throw new IllegalArgumentException("unknown agent " + id);
        cancel.onCancel(n.token::cancel);
        try {
            return n.result.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /** Cancels an agent and all of its subagents. The key is a name, an id, or a story id. */
    public boolean cancel(String key) {
        AgentNode n;
        synchronized (lock) { n = resolve(key); }
        if (n == null || n.state.terminal()) return false;
        n.token.cancel();
        return true;
    }

    // ---- internal waits ----

    /**
     * Registers an internal wait of an agent. The wait counts as an edge in the deadlock check when the holder is
     * not null. An agent in an internal wait is busy: a question to it fails with the reason {@code busy}.
     * Close the result when the wait ends.
     */
    public AutoCloseable beginInternalWait(AgentId waiter, AgentId holder, String reason) {
        var wait = new AgentNode.InternalWait(holder, reason);
        AgentNode n;
        synchronized (lock) {
            n = nodes.get(waiter);
            if (n == null) throw new IllegalArgumentException("unknown agent " + waiter);
            n.internalWaits.add(wait);
        }
        return () -> {
            synchronized (lock) { n.internalWaits.remove(wait); }
        };
    }

    // ---- messages and questions ----

    /** Sends a message with no answer expected. */
    public AskResult message(AgentId from, String to, String text) {
        return message(from, null, to, text);
    }

    /**
     * Sends a message with no answer expected.
     *
     * @param fromName the display name of the sender, or null to use the name of the sender node
     */
    public AskResult message(AgentId from, String fromName, String to, String text) {
        AgentNode sender;
        AgentNode target;
        ExternalDelivery external = null;
        synchronized (lock) {
            sender = nodes.get(from);
            if (sender == null) return AskResult.failure(AskResult.UNKNOWN);
            target = resolve(to);
            if (target == null) {
                if (pending.contains(to)) return AskResult.failure(AskResult.NOT_STARTED);
                external = externalDelivery;
                if (external == null) return AskResult.failure(AskResult.UNKNOWN);
            }
            if (target != null && target.state == AgentLifecycle.CREATED) return AskResult.failure(AskResult.NOT_STARTED);
        }
        if (target == null) {
            var name = fromName == null ? sender.name : fromName;
            return external.deliver(from, name, new AgentId(to), text, false, new CancellationToken())
                .orElse(AskResult.failure(AskResult.UNKNOWN));
        }
        if (target.ended()) return endedResult(target);
        var body = "[message from " + (fromName == null ? sender.name : fromName) + " (" + label(sender) + ")]\n" + text;
        if (!send(sender, target, body, null)) return endedResult(target);
        return AskResult.answer("Message sent to " + target.name + ".");
    }

    private boolean steerRemote(AgentNode n, String text) {
        if (n.ended()) return false;
        n.remote.steer(text);
        return true;
    }

    private boolean send(AgentNode sender, AgentNode target, String text, Question question) {
        if (target.remote != null) return steerRemote(target, text);
        if (target.root) {
            target.input.accept(new AgentInput.PeerMessage(sender.id, text));
            return true;
        }
        return deliver(target, new AgentNode.InboxItem(text, question));
    }

    private static String label(AgentNode n) { return n.storyId() != null ? n.storyId() : n.id.value(); }

    private AskResult endedResult(AgentNode target) {
        if (target.root) return AskResult.ended(null);
        try {
            var completion = target.result.get(Math.max(1, limits.askTimeout().toMillis()), TimeUnit.MILLISECONDS);
            return AskResult.ended(completion.summary());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AskResult.ended(null);
        } catch (ExecutionException | TimeoutException e) {
            return AskResult.ended(null);
        }
    }

    /** Asks a question and waits for the answer. */
    public AskResult ask(AgentId fromId, String to, String text, CancellationToken cancel) {
        return ask(fromId, null, to, text, cancel);
    }

    /**
     * Asks a question and waits for the answer.
     *
     * @param fromName the display name of the sender, or null to use the name of the sender node
     */
    public AskResult ask(AgentId fromId, String fromName, String to, String text, CancellationToken cancel) {
        AgentNode from;
        AgentNode target;
        ExternalDelivery external = null;
        Question q = null;
        synchronized (lock) {
            from = nodes.get(fromId);
            if (from == null) return AskResult.failure(AskResult.UNKNOWN);
            target = resolve(to);
            if (target == null) {
                if (pending.contains(to)) return AskResult.failure(AskResult.NOT_STARTED);
                external = externalDelivery;
                if (external == null) return AskResult.failure(AskResult.UNKNOWN);
            }
        }
        if (target == null) {
            // The lock is not held here, because the answer of an external target can take a long time.
            var name = fromName == null ? from.name : fromName;
            return external.deliver(fromId, name, new AgentId(to), text, true, cancel)
                .orElse(AskResult.failure(AskResult.UNKNOWN));
        }
        synchronized (lock) {
            if (target == from) return AskResult.failure("self");
            if (target.state == AgentLifecycle.CREATED) return AskResult.failure(AskResult.NOT_STARTED);
            if (!target.ended()) {
                if (from.asks >= limits.maxAsks()) return AskResult.failure(AskResult.LIMIT);
                if (from.openAsk != null && from.openAsk.open()) return AskResult.failure(AskResult.OPEN_ASK);
                if (reaches(target, from)) return AskResult.failure(AskResult.DEADLOCK);
                if (!target.internalWaits.isEmpty()) return AskResult.failure(AskResult.BUSY);
                q = new Question("q" + questionCounter.incrementAndGet(), from.id, target.id, text);
                from.openAsk = q;
                from.asks++;
                questions.put(q.id, q);
            }
        }
        if (q == null) return endedResult(target);
        var question = q;
        enterWait(from, AgentLifecycle.WAITING_AGENT, target.name);
        try {
            var body = "[question from " + (fromName == null ? from.name : fromName) + " (" + label(from) + "), id " + question.id + "]\n" + text;
            if (target.request != null && target.request.remote()) {
                askRemote(target, question);
            } else if (!send(from, target, body, question)) {
                question.reply.complete(AskResult.ended(null));
            }
            cancel.onCancel(() -> question.reply.complete(AskResult.failure(AskResult.CANCELLED)));
            try {
                var result = question.reply.get(Math.max(1, limits.askTimeout().toMillis()), TimeUnit.MILLISECONDS);
                if (AskResult.ENDED.equals(result.reason()) && result.summary() == null) return endedResult(target);
                return result;
            } catch (TimeoutException e) {
                question.reply.complete(AskResult.failure(AskResult.TIMEOUT));
                return AskResult.failure(AskResult.TIMEOUT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                question.reply.complete(AskResult.failure(AskResult.CANCELLED));
                return AskResult.failure(AskResult.CANCELLED);
            } catch (ExecutionException | CancellationException e) {
                return AskResult.failure(AskResult.CANCELLED);
            }
        } finally {
            synchronized (lock) {
                from.openAsk = null;
                questions.remove(question.id);
                target.inbox.removeIf(item -> item.question() == question);
            }
            leaveWait(from, AgentLifecycle.WAITING_AGENT);
        }
    }

    /** Sends a question to a remote subagent. The answer or the refusal of the peer finishes the question. */
    private void askRemote(AgentNode target, Question question) {
        target.remoteTask.thenCompose(task -> task.ask(question.id, question.text)).whenComplete((text, error) -> {
            if (error == null) {
                question.reply.complete(AskResult.answer(text == null ? "" : text));
                return;
            }
            var cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
            if (cause instanceof CancellationException) return;
            var message = cause.getMessage();
            question.reply.complete(AskResult.failure(message == null || message.isBlank() ? AskResult.ENDED : message));
        });
    }

    /**
     * Delivers a question of the delegating peer to a hosted subagent. The future finishes with the answer. It
     * finishes with an error (the message is the reason) when the rules refuse the question, on a timeout, or when
     * the subagent ends. The rules are the rules of {@code agent_ask}.
     *
     * @param root the hidden root agent of the delegated tasks
     * @param target the hosted subagent
     */
    public java.util.concurrent.CompletableFuture<String> askFromPeer(AgentId root, AgentId target, String text) {
        Question q;
        AgentNode node;
        synchronized (lock) {
            node = nodes.get(target);
            if (node == null) return refused(AskResult.UNKNOWN);
            if (node.ended()) return refused(AskResult.ENDED);
            if (node.state == AgentLifecycle.CREATED) return refused(AskResult.NOT_STARTED);
            if (reaches(node, nodes.get(root))) return refused(AskResult.DEADLOCK);
            if (!node.internalWaits.isEmpty()) return refused(AskResult.BUSY);
            q = new Question("q" + questionCounter.incrementAndGet(), root, target, text);
            questions.put(q.id, q);
            node.inbox.add(new AgentNode.InboxItem("[question from the delegating peer, id " + q.id + "]\n" + text, q));
        }
        var question = q;
        var timeout = scheduler.schedule(() -> question.reply.complete(AskResult.failure(AskResult.TIMEOUT)),
            Math.max(1, limits.askTimeout().toMillis()), TimeUnit.MILLISECONDS);
        var future = new java.util.concurrent.CompletableFuture<String>();
        question.reply.whenComplete((result, error) -> {
            timeout.cancel(false);
            synchronized (lock) {
                questions.remove(question.id);
                node.inbox.removeIf(item -> item.question() == question);
            }
            if (result != null && result.ok()) future.complete(result.answer());
            else future.completeExceptionally(new IllegalStateException(result == null ? AskResult.CANCELLED
                : result.reason()));
        });
        return future;
    }

    private static java.util.concurrent.CompletableFuture<String> refused(String reason) {
        return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException(reason));
    }

    /** Refuses a question of a hosted subagent that the delegating peer cannot answer. */
    public boolean answerFailure(AgentId from, String questionId, String reason) {
        Question q;
        synchronized (lock) {
            q = questions.get(questionId);
            if (q == null || !q.to.equals(from)) return false;
        }
        return q.reply.complete(AskResult.failure(reason));
    }

    /** Answers a question that is in the inbox of the caller. */
    public AskResult answer(AgentId from, String questionId, String text) {
        Question q;
        synchronized (lock) {
            q = questions.get(questionId);
            if (q == null) return AskResult.failure("No open question with id " + questionId + ".");
            if (!q.to.equals(from)) return AskResult.failure("The question " + questionId + " is not for you.");
        }
        if (!q.reply.complete(AskResult.answer(text))) {
            return AskResult.failure("The question " + questionId + " is closed.");
        }
        return AskResult.answer("Answer sent.");
    }

    /** True if {@code start} waits, directly or through other agents, for {@code goal}. Must hold the lock. */
    private boolean reaches(AgentNode start, AgentNode goal) {
        var seen = new HashSet<AgentId>();
        var queue = new ArrayDeque<AgentNode>();
        queue.add(start);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            if (current == goal) return true;
            if (!seen.add(current.id)) continue;
            if (current.openAsk != null && current.openAsk.open()) {
                var next = nodes.get(current.openAsk.to);
                if (next != null) queue.add(next);
            }
            for (var wait : current.internalWaits) {
                if (wait.holder() == null) continue;
                var next = nodes.get(wait.holder());
                if (next != null) queue.add(next);
            }
        }
        return false;
    }

    @Override
    public void close() {
        List<AgentNode> all;
        synchronized (lock) { all = new ArrayList<>(nodes.values()); }
        for (var n : all) if (!n.root) n.token.cancel();
        scheduler.shutdownNow();
    }
}
