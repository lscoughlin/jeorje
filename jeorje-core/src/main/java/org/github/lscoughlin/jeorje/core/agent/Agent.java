package org.github.lscoughlin.jeorje.core.agent;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.lang.ref.WeakReference;
import java.util.Optional;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.core.compaction.ContextClassifier;
import org.github.lscoughlin.jeorje.core.compaction.ContextCompactor;
import org.github.lscoughlin.jeorje.core.compaction.ContextEntry;
import org.github.lscoughlin.jeorje.core.compaction.Protection;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.model.Content;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Notes;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.model.Turn;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.compaction.PruningTable;
import org.github.lscoughlin.jeorje.core.session.SessionManifest;
import org.github.lscoughlin.jeorje.core.session.TranscriptReader;
import org.github.lscoughlin.jeorje.core.session.TurnChunker;
import org.github.lscoughlin.jeorje.core.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * One agent. It runs its loop in one virtual thread. The thread reads an input queue that the user,
 * peers, and subagents fill. The agent publishes {@link AgentEvent} objects to a consumer and never
 * exposes its state to the UI. {@link #runTurn} runs one turn on the calling thread.
 */
public final class Agent implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Agent.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** Reads one JSON value and fails when text follows it, so that two calls joined in one text are caught. */
    private static final ObjectReader STRICT = MAPPER.readerFor(JsonNode.class)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** The start of the error text for a call with broken arguments. */
    static final String BROKEN_CALL = "The arguments of your call to ";
    /** The sentence that the input check adds for an empty call. */
    static final String EMPTY_CALL = "The arguments of the call were empty.";
    /** Broken or empty calls in a row at which the agent tells the user. */
    static final int BAD_CALL_WARNING = 2;
    /** The text of the result of a tool call that did not finish. */
    public static final String INTERRUPTED = "interrupted";

    /**
     * A hook at the step boundaries of a turn: before each model call, which is also after each tool call.
     * It never runs inside a tool.
     */
    @FunctionalInterface
    public interface StepBoundary {
        /** Returns texts to add to the working context as user-role messages. Returns an empty list for none. */
        List<String> messages();
    }

    /**
     * A hook that sees every tool call of the model before it runs. It can refuse a call. The hook runs once for
     * each call, so it can count calls.
     */
    @FunctionalInterface
    public interface ToolFilter {
        /** Returns the text of a refusal, which is the error result of the call, or empty to let the call go on. */
        java.util.Optional<String> refuse(ToolUse use);
    }

    /** The live agents by id. The references are weak. Callers use {@link #live} to reach a subagent. */
    private static final Map<AgentId, WeakReference<Agent>> LIVE = new ConcurrentHashMap<>();

    private final ReentrantLock turnLock = new ReentrantLock();
    private static final String TODO_TOOL = "todo";
    private static final String EMPTY_TODO = "Todo list: empty";

    private final AgentId id;
    private final AgentSettings settings;
    private final LlmClient llm;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final PermissionPolicy policy;
    private final ContextStore store;
    private final TranscriptSink transcript;
    private final Consumer<AgentEvent> events;
    private final Clock clock;
    private final ContextBuilder contextBuilder;
    private volatile TurnChunker chunker;
    private final AgentState state = new AgentState();
    private final BlockingQueue<AgentInput> inbox = new LinkedBlockingQueue<>();
    private final Map<String, CompletableFuture<Boolean>> confirmations = new ConcurrentHashMap<>();
    private final List<Turn> completed = new ArrayList<>();
    /** The index in {@code completed} before which turns count as older, because the agent moved them out. */
    private int forcedStart;
    /** Tools that are not read-only but never change the files of the workspace, so they keep the repeat guard. */
    private static final java.util.Set<String> KEEPS_WORKSPACE = java.util.Set.of("ask_user", "todo", "advisor",
        "plan_submit", "skill", "recall", "agent_status", "agent_message", "agent_ask", "agent_answer");
    /** The turn that already published a notice about a request that stays over the budget. */
    private int overBudgetNoticeTurn = -1;
    private int spilledTotal;
    private volatile ContextReport lastReport;
    private volatile LlmRequest lastRequest;

    /**
     * The request of the last model call of this agent: its working context. The advisor tool sends it to the
     * advisor model, so that each agent gets a review of its own context.
     */
    public Optional<LlmRequest> lastRequest() { return Optional.ofNullable(lastRequest); }

    /** How full the context was at the last model call, or a report with no numbers before the first call. */
    public ContextReport contextReport() {
        var report = lastReport;
        return report != null ? report
            : new ContextReport(id.value(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, contextBuilder.budget(), 0, List.of());
    }
    private final List<String> pinned = new ArrayList<>();
    /** The texts of the user for the running turn. {@link #steeringOpen} says whether a turn takes them. */
    private final Queue<String> steering = new ConcurrentLinkedQueue<>();
    private final Object steeringLock = new Object();
    /** True from the start of a turn to its end, so that a text of the user never waits in the queue. */
    private boolean steeringOpen;
    private List<ContextEntry> older = List.of();
    private ContextCompactor compactor;
    private ContextCompactor hardCompactor;
    private String planMode;
    private int turnNumber;
    private volatile CancellationToken current = CancellationToken.none();
    private volatile Thread thread;
    private volatile StepBoundary stepBoundary = List::of;
    private volatile ToolFilter toolFilter = use -> java.util.Optional.empty();
    private final RepeatGuard repeatGuard;
    private volatile Consumer<List<Chunk>> pruneListener = chunks -> { };

    public Agent(AgentId id, AgentSettings settings, LlmClient llm, List<Tool> toolList, PermissionPolicy policy,
                 ContextStore store, TranscriptSink transcript, Consumer<AgentEvent> events, Clock clock) {
        this.id = id;
        this.settings = settings;
        this.llm = llm;
        for (var tool : toolList) tools.put(tool.definition().name(), tool);
        this.policy = policy;
        this.store = store;
        this.transcript = transcript == null ? TranscriptSink.none() : transcript;
        this.events = events;
        this.clock = clock;
        this.contextBuilder = new ContextBuilder(settings.contextWindowTokens(), settings.budgetPercent(),
            settings.maxOutputTokens());
        this.chunker = new TurnChunker(settings.sessionId());
        this.repeatGuard = new RepeatGuard(settings.workingDirectory());
        LIVE.values().removeIf(ref -> ref.get() == null);
        LIVE.put(id, new WeakReference<>(this));
    }

    /** The agent with this id, if it is alive. A later agent with the same id replaces an earlier one. */
    public static Optional<Agent> live(AgentId id) {
        var ref = LIVE.get(id);
        return ref == null ? Optional.empty() : Optional.ofNullable(ref.get());
    }

    /** The settings that the agent uses, including the step limit of a turn. */
    public AgentSettings settings() { return settings; }

    public AgentId id() { return id; }

    public AgentState state() { return state; }

    public ContextBuilder contextBuilder() { return contextBuilder; }

    /** Turns that the agent finished, oldest first. */
    public synchronized List<Turn> turns() { return List.copyOf(completed); }

    /** Turns off compaction when null. */
    public synchronized Agent withCompactor(ContextCompactor next) {
        this.compactor = next;
        return this;
    }

    /**
     * Sets the compactor that {@link #compactNow} uses. It must prune at the hard level, which means a budget so
     * small that every old chunk is over the limit. Null means that {@code compactNow} uses the normal compactor.
     */
    public synchronized Agent withHardCompactor(ContextCompactor next) {
        this.hardCompactor = next;
        return this;
    }

    /** Sets the hook that can refuse a tool call. Null removes the hook. */
    public Agent withToolFilter(ToolFilter filter) {
        this.toolFilter = filter == null ? use -> java.util.Optional.empty() : filter;
        return this;
    }

    /** Sets the hook that adds messages at step boundaries. Null removes the hook. */
    public Agent withStepBoundary(StepBoundary hook) {
        this.stepBoundary = hook == null ? List::of : hook;
        return this;
    }

    /**
     * Sets the listener that receives the chunks that compaction newly pruned. A chunk is newly pruned when it
     * is at a level above 0 after a compaction and was at level 0, or not in the working context, before it.
     * The listener runs on the thread that compacts. Null removes the listener.
     */
    public Agent withPruneListener(Consumer<List<Chunk>> listener) {
        this.pruneListener = listener == null ? chunks -> { } : listener;
        return this;
    }

    /** Adds a pinned item. It stays in every request. */
    public synchronized Agent pin(String item) {
        pinned.add(item);
        return this;
    }

    /** The pinned items, oldest first. */
    public synchronized List<String> pinnedTexts() { return List.copyOf(pinned); }

    /** The pruned working context: one entry for each chunk of the turns that left the recent window. */
    public synchronized List<ContextEntry> workingEntries() { return older; }

    /**
     * Writes the pinned items and the pruning table into a manifest. A later {@link #restore} reads them back.
     */
    public synchronized SessionManifest recordInto(SessionManifest manifest) {
        var items = new ArrayList<SessionManifest.PinnedItem>();
        for (var i = 0; i < pinned.size(); i++) {
            items.add(new SessionManifest.PinnedItem("pin-" + (i + 1), "text", pinned.get(i)));
        }
        return manifest.withPinned(items).withPruning(PruningTable.toEntries(older));
    }

    /**
     * Restores the state of an earlier run of this agent. It sets the finished turns, the turn counter, the pinned
     * items, and the pruned working context, so that the next request equals the request that the earlier run
     * would have made.
     *
     * <p>The turns come from the transcript. Only the turns of this agent count. The text of each chunk comes
     * from the turn, and the pruning table of the manifest gives the class, the level, the kept spans, and the
     * decision note of each pruned chunk. A chunk that has no table row is at level 0. Call this before the agent
     * starts or between turns.
     *
     * @param manifest the manifest of the session, or null for no pins and no pruning
     * @param transcript the complete turns of the transcript, in file order
     */
    public Agent restore(SessionManifest manifest, List<TranscriptReader.TimedTurn> transcript) {
        return restore(manifest, transcript, id);
    }

    /**
     * Restores the state from the turns of another agent id. This is for a session that an earlier run started with
     * a different id for the agent of the user. The chunk ids keep the old agent id, so the pruning table matches.
     * The turns that the agent runs next use the id of this agent and continue the turn numbers.
     *
     * @param source the id of the agent that wrote the turns
     */
    public Agent restore(SessionManifest manifest, List<TranscriptReader.TimedTurn> transcript, AgentId source) {
        turnLock.lock();
        try {
            synchronized (this) {
                completed.clear();
                forcedStart = 0;
                pinned.clear();
                if (manifest != null) {
                    chunker = TurnChunker.forFormat(settings.sessionId(), manifest.chunkFormat());
                    if (manifest.chunkFormat() == SessionManifest.LEGACY_CHUNK_FORMAT) {
                        log.atInfo().log(() -> "Session " + settings.sessionId() + " resumes with chunk format 1");
                    }
                }
                var own = new ArrayList<TranscriptReader.TimedTurn>();
                for (var timed : transcript) {
                    if (timed.turn().agent().equals(source)) own.add(timed);
                }
                var last = 0;
                for (var timed : own) {
                    completed.add(timed.turn());
                    last = Math.max(last, timed.turn().number());
                }
                turnNumber = last;
                var table = manifest == null ? List.<SessionManifest.PruneEntry>of() : manifest.pruning();
                if (manifest != null) manifest.pinned().forEach(item -> pinned.add(item.text()));
                var entries = new ArrayList<ContextEntry>();
                var cutoff = recentStart();
                var chunks = new ArrayList<Chunk>();
                for (var i = 0; i < own.size(); i++) {
                    var timed = own.get(i);
                    chunks.addAll(chunker.chunk(timed.turn(), timed.timestamp()));
                }
                var ctx = new ContextClassifier.Context(last, settings.recentTurns(), Set.of(), Set.of(),
                    Map.of(), settings.maxProtectedTokens());
                var classes = new ContextClassifier().classify(chunks, ctx);
                var leaving = new HashSet<Integer>();
                for (var i = 0; i < cutoff; i++) leaving.add(own.get(i).turn().number());
                var leaves = chunks.stream().filter(c -> leaving.contains(c.turn())).toList();
                entries.addAll(PruningTable.restore(leaves, table,
                    c -> classes.getOrDefault(c.chunkId(), Protection.AGGRESSIVE)));
                older = List.copyOf(entries);
            }
        } finally {
            turnLock.unlock();
        }
        return this;
    }

    /**
     * Starts a fresh conversation in this agent. It forgets the finished turns, the pinned items, and the pruned
     * working context, so that the next request holds only the new input and the system prompt. The turn counter
     * stays where it is, because the transcript and the index keep the earlier turns: the next turn has a number
     * above every number on disk, so a chunk id is never used twice. It leaves the plan-mode section and the
     * compactor in place, and it publishes an {@link AgentEvent.Cleared} event.
     *
     * <p>When a turn runs, the call waits for the turn and its compaction to end, then clears. The wait is at most
     * {@code timeoutMs}. A turn that is still running after the timeout keeps the clear out: the call changes
     * nothing and returns -1.
     *
     * @param timeoutMs the longest time to wait for a running turn, in milliseconds
     * @return the number of turns that the agent forgot, or -1 when a turn was still running
     * @throws InterruptedException when the thread is interrupted while it waits
     */
    public int clear(long timeoutMs) throws InterruptedException {
        if (!turnLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) return -1;
        try {
            int forgotten;
            synchronized (this) {
                forgotten = completed.size();
                completed.clear();
                forcedStart = 0;
                pinned.clear();
                repeatGuard.reset();
                older = List.of();
            }
            steering.clear();
            publish(new AgentEvent.Cleared(id, forgotten));
            return forgotten;
        } finally {
            turnLock.unlock();
        }
    }

    /** {@link #clear(long)} with a wait of one second. */
    public int clear() {
        try {
            return clear(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /**
     * Adds a text that the running turn takes at its next step boundary. The user steers a turn this way: the
     * text reaches the model before its next model call, and the turn continues. The agent publishes an
     * {@link AgentEvent.Steered} event that shows the text as a message of the user.
     *
     * <p>A text that arrives after the last step boundary of a turn is not lost: when the turn ended on its own,
     * the text runs as a new turn (see {@link #runTurn(String, int)}). A text that arrives when the turn is
     * cancelled waits for the first step boundary of the next turn. A text that arrives when no turn runs is not
     * queued: the call returns false, and the caller starts a turn with the text. This keeps a text from waiting
     * in the queue for a turn that may never come.
     *
     * <p>A turn that takes a text at a step boundary starts a new count of model calls, so the step limit gives
     * the turn a new budget. The user can therefore steer a turn that used its budget to its end.
     *
     * @return true when a turn runs and takes the text at its next step boundary
     */
    public boolean steer(String text) {
        if (text == null || text.isBlank()) return false;
        var clean = text.strip();
        boolean running;
        synchronized (steeringLock) {
            running = steeringOpen;
            if (running) steering.add(clean);
        }
        if (!running) return false;
        publish(new AgentEvent.Steered(id, clean, true));
        return true;
    }

    /**
     * True while a turn can take a text of the user at its next step boundary. The input of the interface uses it
     * to decide between steering a turn and starting a turn.
     */
    public boolean turnActive() {
        synchronized (steeringLock) {
            return steeringOpen;
        }
    }

    private void openSteering() {
        synchronized (steeringLock) {
            steeringOpen = true;
        }
    }

    /**
     * Closes the steering window of a turn and returns the texts that no step boundary took.
     */
    private List<String> closeSteering() {
        synchronized (steeringLock) {
            steeringOpen = false;
            var late = new ArrayList<String>();
            for (var text = steering.poll(); text != null; text = steering.poll()) late.add(text);
            return late;
        }
    }

    /** Puts texts back in the queue, so that the first step boundary of the next turn takes them. */
    private void requeue(List<String> texts) {
        synchronized (steeringLock) {
            steering.addAll(texts);
        }
    }

    /** Sets the plan-mode section of the system prompt, or null to leave plan mode. */
    public synchronized Agent planMode(String section) {
        this.planMode = section;
        return this;
    }

    // ---- thread and queue ----

    /** Starts the agent thread. */
    public synchronized void start() {
        if (thread != null) return;
        thread = Thread.ofVirtual().name("agent-" + id).start(this::run);
    }

    /** Adds input to the queue. */
    public void submit(AgentInput input) { inbox.add(input); }

    /** Cancels the running turn. The agent stays usable. */
    public void cancel() { current.cancel(); }

    /** Answers a {@link AgentEvent.ConfirmationRequested} event. */
    public void confirm(String toolUseId, boolean approved) {
        var future = confirmations.get(toolUseId);
        if (future != null) future.complete(approved);
    }

    /** Stops the thread after it cancels the running turn. */
    @Override
    public void close() {
        inbox.add(new AgentInput.Stop());
        current.cancel();
        var t = thread;
        if (t != null) {
            try {
                t.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run() {
        try {
            while (true) {
                var input = inbox.take();
                if (input instanceof AgentInput.Stop) return;
                try {
                    runTurn(input.text());
                } catch (RuntimeException e) {
                    log.atError().setCause(e).log(() -> "Agent " + id + " failed in a turn");
                    publish(new AgentEvent.Error(id, "Internal error: " + message(e)));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- the loop ----

    /** Runs one turn to its end on the calling thread. */
    public void runTurn(String input) {
        runTurn(input, settings.stepLimit());
    }

    /**
     * Runs one turn on the calling thread with a budget of model calls.
     *
     * <p>The budget counts the model calls since the last text of the user that the turn took at a step
     * boundary. A text of the user therefore starts a new count and the turn continues with a full budget. The
     * budget stays a guard against a model that loops with no input from the user.
     *
     * <p>A text of the user that arrives after the last step boundary of a turn runs as a new turn, so no text
     * is lost. That turn runs in the same call.
     *
     * @return the number of model calls that the turn made in all. A result equal to {@code maxModelCalls} means
     *         that the turn used a full budget up. The result is above {@code maxModelCalls} when a text of the
     *         user started a new count. The turn may have ended on its own at the same time.
     */
    public int runTurn(String input, int maxModelCalls) {
        turnLock.lock();
        try {
            var limit = Math.min(maxModelCalls, settings.stepLimit());
            var total = 0;
            var pending = input;
            while (true) {
                var outcome = runOneTurn(pending, limit);
                total += outcome.calls();
                if (outcome.late().isEmpty()) return total;
                if (outcome.cancelled()) {
                    // The user ended the turn. The texts wait for the first step boundary of the next turn.
                    requeue(outcome.late());
                    return total;
                }
                pending = String.join("\n", outcome.late());
            }
        } finally {
            turnLock.unlock();
        }
    }

    /**
     * The result of one turn.
     *
     * @param calls the model calls of the turn
     * @param cancelled true when the user cancelled the turn
     * @param late the texts of the user that arrived after the last step boundary of the turn
     */
    private record TurnOutcome(int calls, boolean cancelled, List<String> late) {}

    private TurnOutcome runOneTurn(String input, int limit) {
        var token = new CancellationToken();
        current = token;
        token.onCancel(() -> confirmations.values().forEach(f -> f.cancel(false)));
        openSteering();
        try {
            var number = ++turnNumber;
            var messages = new ArrayList<Message>();
            messages.add(Message.user(input));
            flushed = 0;
            var calls = 0;
            var total = 0;
            var emptyReplies = 0;
            var spill = new TurnSpill(store, settings.sessionId(), id, number, clock);
            while (true) {
                if (token.isCancelled()) break;
                // A text of the user starts a new count, so the step limit gives the turn a new budget.
                if (addBoundaryMessages(messages)) calls = 0;
                if (calls >= limit) {
                    publish(new AgentEvent.Error(id, "Step limit reached: " + limit
                        + " model calls in one turn. The turn ends."));
                    break;
                }
                calls++;
                total++;
                var built = contextBuilder.build(new ContextBuilder.Input(systemPrompt(), pinnedItems(messages),
                    olderEntries(), recentTurns(), messages, definitions()));
                if (built.estimate() > spillTrigger(built.budget())) {
                    var target = (long) (built.budget() * SPILL_TARGET_PERCENT / 100.0);
                    var chars = (long) ((built.estimate() - target) * 4 / Math.max(0.25, contextBuilder.scale()));
                    var outcome = spill.spill(messages, chunker, chars);
                    if (outcome.spilled() > 0) {
                        spilledTotal += outcome.spilled();
                        log.atDebug().log(() -> "spilled " + outcome.spilled() + " tool results of turn " + number);
                        publish(new AgentEvent.Compacted(id, outcome.spilled(), outcome.freedChars() / 4));
                        built = contextBuilder.build(new ContextBuilder.Input(systemPrompt(), pinnedItems(messages),
                            olderEntries(), recentTurns(), messages, definitions()));
                    }
                }
                built = fitRecent(built, messages);
                lastReport = ContextReport.of(id.value(), new ContextBuilder.Input(systemPrompt(),
                    pinnedItems(messages), olderEntries(), recentTurns(), messages, definitions()), built, spilledTotal);
                publish(new AgentEvent.ContextUsage(id, built.estimate(), built.budget()));
                lastRequest = built.request();
                flushMessages(messages, spill, number);
                var assistant = callModel(built, token);
                if (assistant.error() != null) {
                    publish(new AgentEvent.Error(id, assistant.error()));
                    break;
                }
                if (!assistant.content().isEmpty()) messages.add(new Message(Role.ASSISTANT, assistant.content()));
                flushMessages(messages, spill, number);
                var uses = assistant.content().stream().filter(ToolUse.class::isInstance).map(ToolUse.class::cast)
                    .toList();
                if (token.isCancelled()) {
                    closeOpenCalls(messages, uses, List.of());
                    break;
                }
                if (uses.isEmpty() && isBlank(assistant.content())) {
                    emptyReplies++;
                    var count = emptyReplies;
                    log.atWarn().log(() -> "The model gave an empty reply (" + count + " in a row) in turn " + number);
                    if (emptyReplies > MAX_EMPTY_RETRIES) {
                        publish(new AgentEvent.Error(id, "The model gave an empty reply " + emptyReplies
                            + " times. The turn ends."));
                        break;
                    }
                    addNote(messages, EMPTY_REPLY_NOTE, flushed);
                    continue;
                }
                emptyReplies = 0;
                if (uses.isEmpty()) break;
                var results = dispatch(uses, token, assistant.broken());
                messages.add(new Message(Role.USER, List.copyOf(results)));
                noteBadCalls(results);
                if (token.isCancelled()) break;
            }
            var finished = new Turn(id, number, messages);
            finishTurn(finished, spill.full(finished));
            return new TurnOutcome(total, token.isCancelled(), closeSteering());
        } catch (RuntimeException | Error e) {
            closeSteering();
            throw e;
        }
    }

    /**
     * Asks the step boundary hook for messages. A message joins the last user message, so that a tool result
     * always follows its tool use directly. Otherwise it becomes a new user message. The texts of the user that
     * arrived while the turn runs come first, as one user message.
     *
     * @return true when the turn took at least one text of the user, which starts a new count of model calls
     */
    private boolean addBoundaryMessages(List<Message> messages) {
        var steered = addSteeringTexts(messages);
        List<String> extra;
        try {
            extra = stepBoundary.messages();
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "The step boundary hook failed: " + message(e));
            return steered;
        }
        if (extra == null || extra.isEmpty()) return steered;
        var texts = extra.stream().filter(t -> t != null && !t.isEmpty()).<Content>map(Text::new).toList();
        if (texts.isEmpty()) return steered;
        var last = messages.isEmpty() ? null : messages.getLast();
        if (last != null && last.role() == Role.USER && messages.size() > flushed) {
            var merged = new ArrayList<>(last.content());
            merged.addAll(texts);
            messages.set(messages.size() - 1, new Message(Role.USER, merged));
        } else {
            messages.add(new Message(Role.USER, texts));
        }
        return steered;
    }

    /** The number of messages of the running turn that the transcript has. Those messages do not change any more. */
    private int flushed;

    /**
     * Gives the transcript the messages that cannot change any more, so that a long turn can be read while it
     * runs. A spilled result goes with its full text. {@code finishTurn} writes the rest and the end of the turn.
     */
    private void flushMessages(List<Message> messages, TurnSpill spill, int number) {
        if (messages.size() <= flushed) return;
        try {
            var source = spill.active() ? spill.full(new Turn(id, number, messages)).messages() : messages;
            var now = clock.instant();
            while (flushed < messages.size()) transcript.writeMessage(id, number, source.get(flushed++), now);
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "Cannot write a message to the transcript: " + message(e));
        }
    }

    /** The most empty replies in a row that the agent answers with a note before it ends the turn. */
    static final int MAX_EMPTY_RETRIES = 2;
    static final String EMPTY_REPLY_NOTE =
        "Your last reply was empty. Continue with the task. If you are done, say so.";

    private static boolean isBlank(List<Content> content) {
        return content.stream().noneMatch(c -> !(c instanceof Text t) || !t.text().isBlank());
    }

    /**
     * Adds a system note to the turn. The note joins the last user message, so that a tool result always follows
     * its tool use directly. Otherwise it becomes a new user message.
     */
    static void addNote(List<Message> messages, String text) {
        addNote(messages, text, 0);
    }

    /** As {@link #addNote(List, String)}, but a message with an index below {@code frozen} is not changed. */
    static void addNote(List<Message> messages, String text, int frozen) {
        var note = new Text(Notes.of(text));
        var last = messages.isEmpty() ? null : messages.getLast();
        if (last != null && last.role() == Role.USER && messages.size() > frozen) {
            var merged = new ArrayList<>(last.content());
            merged.add(note);
            messages.set(messages.size() - 1, new Message(Role.USER, merged));
        } else {
            messages.add(new Message(Role.USER, List.of(note)));
        }
    }

    /**
     * Moves the texts that the user typed while the turn runs into the context. They go last, so the model reads
     * them after the tool results of the step that just ended.
     *
     * @return true when there was at least one text
     */
    private boolean addSteeringTexts(List<Message> messages) {
        var texts = new ArrayList<Content>();
        for (var next = steering.poll(); next != null; next = steering.poll()) texts.add(new Text(next));
        if (texts.isEmpty()) return false;
        var last = messages.isEmpty() ? null : messages.getLast();
        if (last != null && last.role() == Role.USER && messages.size() > flushed
            && last.content().stream().allMatch(ToolResult.class::isInstance)) {
            var merged = new ArrayList<>(last.content());
            merged.addAll(texts);
            messages.set(messages.size() - 1, new Message(Role.USER, merged));
        } else {
            messages.add(new Message(Role.USER, texts));
        }
        return true;
    }

    private void closeOpenCalls(List<Message> messages, List<ToolUse> uses, List<ToolResult> done) {
        var answered = new HashSet<String>();
        for (var r : done) answered.add(r.toolUseId());
        var open = new ArrayList<Content>(done);
        for (var use : uses) {
            if (answered.add(use.id())) {
                open.add(new ToolResult(use.id(), INTERRUPTED, true));
                publish(new AgentEvent.ToolFinished(id, use.id(), ToolResultData.error(INTERRUPTED)));
            }
        }
        if (!open.isEmpty()) messages.add(new Message(Role.USER, open));
    }

    private synchronized List<Turn> recentTurns() {
        return List.copyOf(completed.subList(recentStart(), completed.size()));
    }

    /**
     * The index in {@code completed} of the first recent turn. A recent turn stays verbatim in the request. The
     * window has at most {@code recentTurns} turns and uses at most {@code recentMaxPercent} of the budget, but the
     * last finished turn is always recent, unless the agent moved it out to make room (see {@link #fitRecent}).
     */
    private synchronized int recentStart() {
        var n = completed.size();
        var start = Math.max(0, n - settings.recentTurns());
        var limit = (long) contextBuilder.budget() * settings.recentMaxPercent() / 100;
        var tokens = 0L;
        for (var i = n - 1; i >= start; i--) {
            for (var m : completed.get(i).messages()) tokens += ContextBuilder.estimate(m);
            if (tokens > limit && i < n - 1) {
                start = i + 1;
                break;
            }
        }
        return Math.min(n, Math.max(start, forcedStart));
    }

    /** The pinned items of the user, and then the latest todo list. The list is read from the history of the agent. */
    private synchronized List<String> pinnedItems(List<Message> current) {
        var items = new ArrayList<>(pinned);
        var decisions = Decisions.build(completed);
        if (decisions != null) items.add(decisions);
        var todo = latestTodo(current);
        if (todo != null) items.add(todo);
        return List.copyOf(items);
    }

    /**
     * The text of the latest successful {@code todo} result in the current messages and the finished turns, or
     * null. The agent pins this text in each request, so that pruning never removes the list.
     */
    private String latestTodo(List<Message> current) {
        var found = latestTodo(current, null);
        for (var i = completed.size() - 1; found == null && i >= 0; i--) {
            found = latestTodo(completed.get(i).messages(), null);
        }
        return found == null || found.startsWith(EMPTY_TODO) ? null : found;
    }

    private static String latestTodo(List<Message> messages, String none) {
        var tools = new java.util.HashMap<String, String>();
        for (var m : messages) {
            for (var c : m.content()) if (c instanceof ToolUse u) tools.put(u.id(), u.tool());
        }
        for (var i = messages.size() - 1; i >= 0; i--) {
            var content = messages.get(i).content();
            for (var k = content.size() - 1; k >= 0; k--) {
                if (content.get(k) instanceof ToolResult r && !r.error() && TODO_TOOL.equals(tools.get(r.toolUseId()))) {
                    return r.output().strip();
                }
            }
        }
        return none;
    }

    private synchronized List<String> pinnedItems() { return List.copyOf(pinned); }

    private synchronized List<ContextEntry> olderEntries() { return older; }

    private List<org.github.lscoughlin.jeorje.core.model.ToolDefinition> definitions() {
        return tools.values().stream().map(Tool::definition).toList();
    }

    private String systemPrompt() {
        String plan;
        synchronized (this) {
            plan = planMode;
        }
        return SystemPromptBuilder.build(new SystemPromptBuilder.Parts(settings.systemPrompt(), definitions(),
            settings.workingDirectory(), System.getProperty("os.name", "unknown"), LocalDate.now(clock),
            settings.instructionsFor(settings.workingDirectory()), plan));
    }

    /** The share of the budget at which a spill of the turn that runs starts, from the soft trigger. */
    private long spillTrigger(int budget) {
        var compaction = compactor;
        var percent = compaction == null ? 50 : compaction.settings().softPercent();
        return (long) budget * percent / 100;
    }

    /**
     * Makes room when the request does not fit the budget, and a recent turn would leave it. The agent first moves
     * the oldest recent turn to the older entries and prunes at the hard level, one turn at a time. A turn leaves
     * the request only when pruning does not make enough room. The request then has a line that tells the model
     * about it. The agent logs a warning and publishes {@link AgentEvent.ContextTrimmed} for the user.
     */
    private ContextBuilder.Built fitRecent(ContextBuilder.Built first, List<Message> messages) {
        var built = first;
        var pruned = 0;
        var rounds = 0;
        while (built.droppedTurns() > 0 && rounds++ < 100) {
            var count = 0;
            synchronized (this) {
                var start = recentStart();
                if (start >= completed.size()) break;
                forcedStart = start + 1;
                var by = hardCompactor != null ? hardCompactor : compactor;
                if (by == null) break;
                var outcome = prune(by, completed.getLast().number());
                count = outcome == null ? 0 : outcome.chunksPruned();
            }
            pruned += count;
            built = contextBuilder.build(new ContextBuilder.Input(systemPrompt(), pinnedItems(messages),
                olderEntries(), recentTurns(), messages, definitions()));
        }
        var firstOverBudget = built.overBudget() && overBudgetNoticeTurn != turnNumber;
        if (built.overBudget()) overBudgetNoticeTurn = turnNumber;
        if (built.droppedTurns() > 0 || pruned > 0 || firstOverBudget) {
            var result = built;
            var prunedNow = pruned;
            log.atWarn().log(() -> "The request of " + id + " did not fit the budget of " + result.budget()
                + " tokens: " + prunedNow + " chunks pruned, turns " + result.droppedTurnNumbers() + " dropped, "
                + (result.overBudget() ? "still over the budget" : "now inside the budget"));
            publish(new AgentEvent.ContextTrimmed(id, result.droppedTurnNumbers(), prunedNow, result.overBudget()));
        }
        return built;
    }

    /** The share of the budget that a spill tries to reach. */
    static final int SPILL_TARGET_PERCENT = 40;

    /**
     * @param turn the turn as the model saw it: a spilled result is a stub
     * @param full the turn with the full text of every result, for the transcript
     */
    private void finishTurn(Turn turn, Turn full) {
        var now = clock.instant();
        synchronized (this) {
            completed.add(turn);
        }
        try {
            store.append(chunker.chunk(turn, now));
            transcript.writeTurn(full, now);
        } catch (RuntimeException e) {
            log.atError().setCause(e).log(() -> "Cannot store turn " + turn.id());
            publish(new AgentEvent.Error(id, "Cannot store turn " + turn.id() + ": " + message(e)));
        }
        publish(new AgentEvent.TurnFinished(id, turn.number()));
        compact(turn.number());
    }

    /** Moves turns that leave the recent window into the pruned entries and compacts them. */
    private synchronized void compact(int currentTurn) {
        if (recentStart() == 0 && compactor == null) return;
        var outcome = prune(compactor, currentTurn);
        if (outcome != null && outcome.event() != null) publish(outcome.event());
    }

    /**
     * Compacts the working context now, at the hard level. It runs between turns. When a turn runs, it waits
     * for the end of the turn. It uses the hard compactor, or the normal compactor when none is set. The next
     * request is already smaller. It publishes the {@link AgentEvent.Compacted} event when something changed.
     *
     * @return the data of the event. It is zero in both numbers when nothing changed, and then no event is published.
     */
    public AgentEvent.Compacted compactNow() {
        turnLock.lock();
        try {
            synchronized (this) {
                var use = hardCompactor != null ? hardCompactor : compactor;
                var none = new AgentEvent.Compacted(id, 0, 0);
                if (use == null) return none;
                var outcome = prune(use, turnNumber);
                if (outcome == null || outcome.event() == null) return none;
                publish(outcome.event());
                return outcome.event();
            }
        } finally {
            turnLock.unlock();
        }
    }

    /**
     * Adds the turns outside the recent window to the older entries and runs the compactor. At each call, it
     * classifies all chunks of the agent again: the older entries and the chunks of the recent turns. The error
     * state, the immediate file paths, and the concluded searches depend on the later chunks. A chunk that
     * compaction already pruned keeps its class.
     */
    private ContextCompactor.Outcome prune(ContextCompactor by, int currentTurn) {
        var before = new HashSet<String>();
        for (var e : older) if (e.level() > 0) before.add(e.chunk().chunkId());
        var entries = new ArrayList<>(older);
        var known = new HashSet<Integer>();
        for (var e : entries) known.add(e.chunk().turn());
        var cutoff = recentStart();
        forcedStart = Math.max(forcedStart, cutoff);
        for (var i = 0; i < cutoff; i++) {
            var turn = completed.get(i);
            if (!known.add(turn.number())) continue;
            for (var c : chunker.chunk(turn, clock.instant())) entries.add(ContextEntry.of(c, Protection.AGGRESSIVE));
        }
        var all = new ArrayList<Chunk>();
        for (var e : entries) all.add(e.chunk());
        for (var i = Math.max(0, cutoff); i < completed.size(); i++) all.addAll(chunker.chunk(completed.get(i), clock.instant()));
        var protectedTurns = by == null ? settings.recentTurns() : by.settings().protectedRecentTurns();
        var ctx = new ContextClassifier.Context(currentTurn, protectedTurns, Set.of(), Set.of(), Map.of(),
            settings.maxProtectedTokens());
        var classifier = new ContextClassifier();
        var classes = classifier.classify(all, ctx);
        for (var i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            var next = e.level() > 0 ? e.protection() : classes.getOrDefault(e.chunk().chunkId(), Protection.AGGRESSIVE);
            if (next != e.protection()) entries.set(i, new ContextEntry(e.chunk(), next, e.prune()));
        }
        ContextCompactor.Outcome outcome = null;
        if (by != null && !entries.isEmpty()) {
            var paths = classifier.immediatePaths(all, ctx, classes);
            var concluded = classifier.concludedSearches(all, ctx, classes);
            // The compactor treats the last R turns as recent. When the agent keeps fewer recent turns (k), the
            // compactor must see the turns that left the window as old, so it counts the turns as if more had passed.
            var window = completed.size() - cutoff;
            var shifted = currentTurn + Math.max(0, by.settings().recentTurns() - window);
            outcome = by.compact(entries, shifted, paths, concluded);
            entries = new ArrayList<>(outcome.entries());
        }
        older = List.copyOf(entries);
        var pruned = new ArrayList<Chunk>();
        for (var e : older) if (e.level() > 0 && !before.contains(e.chunk().chunkId())) pruned.add(e.chunk());
        if (!pruned.isEmpty()) {
            try {
                pruneListener.accept(List.copyOf(pruned));
            } catch (RuntimeException ex) {
                log.atWarn().log(() -> "The prune listener failed: " + ex.getMessage());
            }
        }
        return outcome;
    }

    // ---- model call ----

    /**
     * The reply of the model.
     *
     * @param broken the tool calls whose arguments were cut off or are not valid JSON: the id of the call and the
     *               error text for the model
     */
    private record Assistant(List<Content> content, String error, Map<String, String> broken) {}

    private Assistant reply(Assembler assembler, String error) {
        return new Assistant(assembler.content(), error, assembler.broken());
    }

    private Assistant callModel(ContextBuilder.Built built, CancellationToken token) {
        var retry = settings.retry();
        for (var attempt = 1; ; attempt++) {
            var assembler = new Assembler(token);
            try {
                stream(built.request(), token, assembler);
                if (assembler.usage != null) contextBuilder.observe(built.rawEstimate(), assembler.usage.inputTokens());
                return reply(assembler, null);
            } catch (CancellationException e) {
                return reply(assembler, null);
            } catch (RuntimeException e) {
                if (token.isCancelled()) return reply(assembler, null);
                var retryable = !assembler.received && retry.isRetryable(e);
                if (!retryable || attempt >= retry.maxAttempts()) {
                    return reply(assembler, "Model call failed: " + message(e));
                }
                var delay = retry.delay(attempt, e);
                var number = attempt;
                log.atDebug().log(() -> "Retry " + number + " in " + delay.toMillis() + " ms after: " + message(e));
                try {
                    settings.sleeper().sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return reply(assembler, null);
                }
            }
        }
    }

    private void stream(LlmRequest request, CancellationToken token, Assembler assembler) {
        var done = new CompletableFuture<Void>();
        var subscription = new java.util.concurrent.atomic.AtomicReference<Flow.Subscription>();
        llm.stream(request, token).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                subscription.set(s);
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(LlmEvent event) {
                try {
                    assembler.accept(event);
                } catch (RuntimeException e) {
                    done.completeExceptionally(e);
                }
            }

            @Override
            public void onError(Throwable t) { done.completeExceptionally(t); }

            @Override
            public void onComplete() { done.complete(null); }
        });
        token.onCancel(() -> {
            var s = subscription.get();
            if (s != null) s.cancel();
            done.complete(null);
        });
        try {
            done.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("interrupted");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new RuntimeException(e.getCause());
        }
    }

    /** Assembles the streamed events into the content of one assistant message. */
    private final class Assembler {
        private final CancellationToken token;
        private final StringBuilder text = new StringBuilder();
        private final Map<String, String> names = new LinkedHashMap<>();
        private final Map<String, StringBuilder> inputs = new LinkedHashMap<>();
        volatile LlmEvent.Usage usage;
        volatile boolean received;
        volatile String finishReason;

        Assembler(CancellationToken token) { this.token = token; }

        void accept(LlmEvent event) {
            received = true;
            switch (event) {
                case LlmEvent.TextDelta d -> {
                    text.append(d.text());
                    publish(new AgentEvent.TokenDelta(id, d.text()));
                }
                case LlmEvent.ToolUseStart s -> {
                    names.put(s.id(), s.tool());
                    inputs.putIfAbsent(s.id(), new StringBuilder());
                }
                case LlmEvent.ToolUseInput i -> inputs.computeIfAbsent(i.id(), k -> new StringBuilder())
                    .append(i.jsonFragment());
                case LlmEvent.MessageStop s -> finishReason = s.stopReason();
                case LlmEvent.Usage u -> usage = u;
            }
        }

        /**
         * The tool calls whose arguments are not a valid JSON object: the id and the error text for the model. A
         * call with blank arguments is not in the map. The input check of the tool handles it. Each broken call is
         * in the log with the start and the end of its raw text, so that someone can find out whether the provider
         * or Jeorje lost the arguments.
         */
        Map<String, String> broken() {
            var out = new LinkedHashMap<String, String>();
            if (token.isCancelled()) return out;
            for (var entry : names.entrySet()) {
                var raw = inputs.get(entry.getKey()).toString();
                if (raw.isBlank()) continue;
                String problem;
                try {
                    JsonNode value = STRICT.readValue(raw);
                    if (value.isObject()) continue;
                    problem = "The arguments are not a JSON object.";
                } catch (RuntimeException e) {
                    var text = String.valueOf(e.getMessage());
                    problem = text.contains("end-of-input") ? "The stream ended in the middle of the arguments."
                        : "The arguments are not valid JSON.";
                }
                var cut = "length".equals(finishReason);
                var reason = cut ? "The output limit of the model was reached, so the call was cut off. "
                    + "Make the call shorter or split the work in steps." : problem;
                var tool = entry.getValue();
                var chars = raw.length();
                var reasonName = finishReason;
                var shown = problem;
                var head = raw.substring(0, Math.min(200, chars));
                var tail = raw.substring(Math.max(0, chars - 200));
                log.atWarn().log(() -> "Broken tool call " + tool + " (" + chars + " characters, finish reason "
                    + reasonName + "): " + shown + " Start: " + head + " End: " + tail);
                out.put(entry.getKey(), BROKEN_CALL + tool + " were not valid JSON (" + chars
                    + " characters were received). " + reason + " Send the call again.");
            }
            return out;
        }

        List<Content> content() {
            var out = new ArrayList<Content>();
            if (!text.isEmpty()) out.add(new Text(text.toString()));
            for (var entry : names.entrySet()) out.add(new ToolUse(entry.getKey(), entry.getValue(),
                parse(inputs.get(entry.getKey()).toString())));
            return out;
        }

        private JsonNode parse(String json) {
            if (json.isBlank()) return MAPPER.createObjectNode();
            try {
                return MAPPER.readTree(json);
            } catch (RuntimeException e) {
                log.atDebug().log(() -> "Tool input is not valid JSON" + (token.isCancelled() ? " (cancelled)" : ""));
                return MAPPER.createObjectNode();
            }
        }
    }

    // ---- tool dispatch ----

    /**
     * Counts the steps in a row in which a call had broken or empty arguments. The second step in a row gives an
     * error event, because a model or a provider that cuts the arguments of calls needs a change that only the user
     * can make. A step with no such call starts the count again.
     */
    private void noteBadCalls(List<ToolResult> results) {
        var bad = results.stream().anyMatch(r -> r.error()
            && (r.output().startsWith(BROKEN_CALL) || r.output().contains(EMPTY_CALL)));
        if (!bad) {
            badSteps = 0;
            return;
        }
        badSteps++;
        if (badSteps == BAD_CALL_WARNING) {
            publish(new AgentEvent.Error(id, "The model sent a tool call with broken arguments " + BAD_CALL_WARNING
                + " times in a row. The provider may be at fault. Try another model or provider."));
        }
    }

    private int badSteps;

    private List<ToolResult> dispatch(List<ToolUse> uses, CancellationToken token, Map<String, String> broken) {
        var allReadOnly = uses.size() > 1 && uses.stream().allMatch(u -> tools.get(u.tool()) != null
            && tools.get(u.tool()).readOnly());
        var results = new ArrayList<ToolResult>();
        if (allReadOnly) {
            var gates = new ArrayList<ToolResultData>();
            for (var use : uses) gates.add(token.isCancelled() ? interrupted() : preflight(use, token, broken));
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<CompletableFuture<ToolResultData>>();
                for (var i = 0; i < uses.size(); i++) {
                    var use = uses.get(i);
                    var gate = gates.get(i);
                    futures.add(gate != null ? CompletableFuture.completedFuture(gate)
                        : CompletableFuture.supplyAsync(() -> execute(use, token), pool));
                }
                for (var i = 0; i < uses.size(); i++) {
                    var data = futures.get(i).join();
                    results.add(finish(uses.get(i), data));
                }
            }
        } else {
            for (var use : uses) {
                var data = token.isCancelled() ? interrupted() : preflight(use, token, broken);
                if (data == null) data = execute(use, token);
                results.add(finish(use, data));
            }
        }
        return results;
    }

    /**
     * Runs the checks before a call: the tool filter, the repeat guard, and then the permission. Returns the result
     * when the call must not run, or null when it can run.
     */
    private ToolResultData preflight(ToolUse use, CancellationToken token, Map<String, String> broken) {
        var cut = broken.get(use.id());
        if (cut != null) return ToolResultData.error(cut);
        var refusal = toolFilter.refuse(use);
        if (refusal.isPresent()) return ToolResultData.error(refusal.get());
        if (settings.repeatGuard()) {
            var repeat = repeatGuard.check(use, turnNumber);
            if (repeat != null) return repeat;
        }
        return gate(use, token);
    }

    private ToolResult finish(ToolUse use, ToolResultData data) {
        if (settings.repeatGuard()) {
            var tool = tools.get(use.tool());
            if (tool != null && !tool.readOnly() && !KEEPS_WORKSPACE.contains(use.tool())) repeatGuard.invalidate();
            repeatGuard.record(use, data, turnNumber);
        }
        if (!data.updates().isEmpty()) state.apply(data.updates());
        publish(new AgentEvent.ToolFinished(id, use.id(), data));
        return new ToolResult(use.id(), data.output(), data.error());
    }

    private static ToolResultData interrupted() { return ToolResultData.error(INTERRUPTED); }

    /** Checks the permission. Returns a result when the call must not run, or null when it can run. */
    private ToolResultData gate(ToolUse use, CancellationToken token) {
        var decision = policy.check(use);
        return switch (decision) {
            case ALLOW -> null;
            case DENY -> denied(use);
            case ASK -> {
                var future = new CompletableFuture<Boolean>();
                confirmations.put(use.id(), future);
                try {
                    if (token.isCancelled()) future.cancel(false);
                    publish(new AgentEvent.ConfirmationRequested(id, use));
                    yield future.get() ? null : denied(use);
                } catch (CancellationException e) {
                    yield interrupted();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    yield interrupted();
                } catch (ExecutionException e) {
                    yield ToolResultData.error(message(e.getCause()));
                } finally {
                    confirmations.remove(use.id());
                }
            }
        };
    }

    private static ToolResultData denied(ToolUse use) {
        return ToolResultData.error("Permission denied: the call to " + use.tool() + " did not run.");
    }

    private ToolResultData execute(ToolUse use, CancellationToken token) {
        var tool = tools.get(use.tool());
        if (tool == null) return ToolResultData.error("Unknown tool: " + use.tool());
        publish(new AgentEvent.ToolStarted(id, use.id(), use.tool(), summary(use)));
        try {
            var context = new ToolContext(settings.workingDirectory(), id, token, store, clock);
            var input = use.input() == null ? MAPPER.createObjectNode() : use.input();
            var invalid = tool.check(input);
            if (invalid.isPresent()) return ToolResultData.error(invalid.get());
            var result = tool.execute(input, context);
            if (result == null) return ToolResultData.error("Tool " + use.tool() + " returned no result.");
            return token.isCancelled() && result.error() ? interrupted() : result;
        } catch (CancellationException e) {
            return interrupted();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return interrupted();
        } catch (Exception e) {
            if (token.isCancelled()) return interrupted();
            log.atDebug().log(() -> "Tool " + use.tool() + " failed: " + message(e));
            return ToolResultData.error(message(e));
        }
    }

    private static String summary(ToolUse use) {
        var input = use.input() == null ? "" : use.input().toString();
        return use.tool() + (input.length() > 80 ? " " + input.substring(0, 80) + "..." : " " + input);
    }

    private static String message(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private void publish(AgentEvent event) {
        try {
            events.accept(event);
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "An event consumer failed: " + message(e));
        }
    }
}
