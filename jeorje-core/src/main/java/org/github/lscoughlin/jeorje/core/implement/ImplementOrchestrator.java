package org.github.lscoughlin.jeorje.core.implement;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.agent.Agent;
import org.github.lscoughlin.jeorje.core.agent.AgentInput;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.agent.TranscriptSink;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.plan.Plan;
import org.github.lscoughlin.jeorje.core.plan.PlanException;
import org.github.lscoughlin.jeorje.core.plan.PlanParser;
import org.github.lscoughlin.jeorje.core.plan.PlanWriter;
import org.github.lscoughlin.jeorje.core.plan.Story;
import org.github.lscoughlin.jeorje.core.plan.Task;
import org.github.lscoughlin.jeorje.core.plan.TaskState;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.subagent.Completion;
import org.github.lscoughlin.jeorje.core.subagent.CompletionSteps;
import org.github.lscoughlin.jeorje.core.subagent.SpawnMode;
import org.github.lscoughlin.jeorje.core.subagent.SpawnRequest;
import org.github.lscoughlin.jeorje.core.subagent.SubagentEvent;
import org.github.lscoughlin.jeorje.core.subagent.SubagentLimits;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.core.worktree.Verifier;
import org.github.lscoughlin.jeorje.core.worktree.Worktree;
import org.github.lscoughlin.jeorje.core.worktree.WorktreeCompletion;
import org.github.lscoughlin.jeorje.core.worktree.WorktreeFinisher;
import org.github.lscoughlin.jeorje.core.worktree.WorktreeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The orchestrator of {@code /implement}. It loads a plan, asks for approval, schedules one story-scoped worker for
 * each story, runs the completion sequence of each worker (sync, verification, review, merge back, final summary,
 * cleanup), retries failed stories, blocks stories that cannot finish, and reports the result.
 *
 * <p>A worker is a subagent of the {@link SubagentRuntime} that the orchestrator owns. The orchestrator itself is
 * the root agent of this runtime, named {@code orchestrator}. The working context of a worker has the normal system
 * prompt and one user message with the text of its story. The orchestrator keeps the plan and the states. Workers
 * read them with {@code story_read}.
 */
public final class ImplementOrchestrator implements StoryHost, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ImplementOrchestrator.class);

    /** The name of the orchestrator agent. The agent id is unique in the process, see {@link #agentId()}. */
    public static final String NAME = "orchestrator";
    /** The ids that live orchestrators of this process use. */
    private static final Set<String> USED_IDS = ConcurrentHashMap.newKeySet();
    /** The tools that the model of the orchestrator gets. It has no tool that changes a file. */
    public static final Set<String> ORCHESTRATOR_TOOLS = Set.of("read", "ls", "glob", "grep", "recall", "story_read",
        "agent_status", "agent_message", "agent_ask", "agent_answer", "advisor", "ask_user");
    /** The default largest number of model calls in one turn of the orchestrator. */
    public static final int DEFAULT_TURN_CALLS = 30;
    /** The tools that a worker never gets. */
    public static final Set<String> EXCLUDED_TOOLS = Set.of(SubagentRuntime.SPAWN_TOOL, "plan_submit", "ask_user");

    /** Tools of the workers that Jeorje runs itself. They are allowed when the base policy would ask. */
    static final Set<String> INTERNAL_TOOLS = Set.of("task_done", "story_done", "story_blocked", "story_read",
        "sync_done", "sync_abort");

    private static final Pattern LABEL = Pattern.compile("^(Outcome|Done|Files|Branch|Decisions|Verification|"
        + "Open issues|Keywords):\\s*(.*)$");
    /** The lines that every worker gets after its story text. */
    public static final String WORKER_RULES = """
        ### Work rules
        - Call task_done right after you finish each task.
        - Read files with the read and grep tools. Do not use sed or grep in bash to read files.""";
    private static final int OUTPUT_TAIL = 4_000;
    private static final int MAX_NUDGES = 2;
    private static final int MAX_VERIFY_FIXES = 2;

    private final String sessionId;
    private final Path projectRoot;
    private final JeorjeConfig.Implement config;
    private final ContextStore store;
    private final WorktreeManager worktrees;
    private final Verifier verifier;
    private final Reviewer reviewer;
    private final Approver approver;
    private final RunStateStore stateStore;
    private final Clock clock;
    private final Duration mergeRetryInterval;
    private final Consumer<ImplementEvent> events;
    private final Consumer<SubagentEvent> subagentEvents;
    private final Consumer<AgentInput> orchestratorInput;
    private final SubagentRuntime runtime;
    private final WorktreeFinisher finisher;
    private final List<String> toolNames;
    private final AgentId agentId;
    private final OrchestratorLoop loop;
    private final List<String> personQuestions = new CopyOnWriteArrayList<>();
    private final CancellationToken rootToken = new CancellationToken();
    private final ReentrantLock spawnGate = new ReentrantLock();
    private final List<AgentInput> rootMessages = new CopyOnWriteArrayList<>();
    private final AtomicInteger noteCounter = new AtomicInteger();

    private final Object lock = new Object();
    private final Map<String, StoryRun> runs = new LinkedHashMap<>();
    private final Map<String, AgentId> byStory = new ConcurrentHashMap<>();
    private final Map<AgentId, StoryRun> byWorker = new ConcurrentHashMap<>();
    private Plan plan;
    private Path planFile;
    private Set<String> selected = Set.of();
    private boolean active;
    private boolean paused;
    private boolean pausedAnnounced;
    private boolean stopping;
    private CompletableFuture<ImplementReport> future = CompletableFuture.completedFuture(null);

    private ImplementOrchestrator(Builder b) {
        this.sessionId = b.sessionId;
        this.projectRoot = b.settings.workingDirectory();
        this.config = b.config;
        this.store = b.store;
        this.worktrees = b.worktrees;
        this.verifier = b.verifier == null ? Verifier.shell() : b.verifier;
        this.reviewer = b.reviewer;
        this.approver = b.approver;
        this.stateStore = b.stateStore == null ? RunStateStore.memory() : b.stateStore;
        this.clock = b.clock == null ? Clock.systemUTC() : b.clock;
        this.mergeRetryInterval = b.mergeRetryInterval == null ? Duration.ofSeconds(30) : b.mergeRetryInterval;
        this.events = b.events == null ? e -> { } : b.events;
        this.subagentEvents = b.subagentEvents == null ? e -> { } : b.subagentEvents;
        this.orchestratorInput = b.orchestratorInput;
        var limits = (b.limits == null ? SubagentLimits.defaults() : b.limits)
            .withMaxModelCalls(config.maxModelCalls());
        limits = limits.withMaxConcurrent(Math.max(limits.maxConcurrent(), config.parallel()));
        var llm = b.llm;
        SubagentRuntime.LlmProvider gated = model -> {
            // A worker thread asks for its model first. It waits here until the orchestrator made its worktree.
            spawnGate.lock();
            spawnGate.unlock();
            return llm.forModel(model);
        };
        this.runtime = SubagentRuntime.builder(sessionId, b.settings, gated)
            .store(store).transcript(b.transcript).agentEvents(b.agentEvents).events(this::onSubagentEvent)
            .limits(limits).compactor(b.compactor).clock(clock).steps(this::runSteps)
            .workspacePolicies(b.workspacePolicies).advisorLink(b.advisorLink).build();
        this.finisher = worktrees == null ? null : new WorktreeFinisher(worktrees, (parent, note) -> note(note));
        var tools = new LinkedHashMap<String, Tool>();
        for (var tool : b.baseTools) {
            var name = tool.definition().name();
            if (!EXCLUDED_TOOLS.contains(name)) tools.put(name, tool);
        }
        if (b.workerTools != null) {
            for (var tool : b.workerTools.create(new WorkerToolsFactory.Context(this, runtime, worktrees, sessionId))) {
                var name = tool.definition().name();
                if (!EXCLUDED_TOOLS.contains(name)) tools.put(name, tool);
            }
        }
        this.toolNames = List.copyOf(tools.keySet());
        this.agentId = new AgentId(reserveId(b.agentId != null ? b.agentId.value() : b.sessionId + "/" + NAME));
        var own = new LinkedHashMap<String, Tool>();
        for (var tool : b.baseTools) {
            if (ORCHESTRATOR_TOOLS.contains(tool.definition().name())) own.put(tool.definition().name(), tool);
        }
        for (var tool : tools.values()) {
            if (ORCHESTRATOR_TOOLS.contains(tool.definition().name())) own.put(tool.definition().name(), tool);
        }
        // With no user, the orchestrator cannot ask one. A question that needs a person goes to the report.
        if (!b.interactive) own.remove("ask_user");
        var answerTool = own.get("agent_answer");
        if (answerTool != null) own.put("agent_answer", new AnswerRecorder(answerTool));
        var base = b.policy == null ? (PermissionPolicy) a -> PermissionPolicy.Decision.ALLOW : b.policy;
        // The story tools and the sync tools change no file of the workspace. They never ask the user.
        PermissionPolicy rootPolicy = action -> {
            var decision = base.check(action);
            return decision == PermissionPolicy.Decision.ASK && INTERNAL_TOOLS.contains(action.tool())
                ? PermissionPolicy.Decision.ALLOW : decision;
        };
        runtime.registerRoot(agentId, NAME, rootToken, List.copyOf(tools.values()), rootPolicy,
            this::onRootInput, () -> 1);
        runtime.questionFallback(agentId, OrchestratorPrompt.NO_ANSWER);
        var agentEvents = b.agentEvents;
        this.loop = new OrchestratorLoop(agentId, runtime, b.settings, () -> llm.forModel(null),
            List.copyOf(own.values()), rootPolicy, store, b.transcript,
            agentEvents == null ? e -> { } : agentEvents, clock, this::orchestratorContext,
            b.turnCalls > 0 ? b.turnCalls : DEFAULT_TURN_CALLS, b.interactive);
    }

    private static String reserveId(String wanted) {
        var candidate = wanted;
        for (var n = 2; !USED_IDS.add(candidate); n++) candidate = wanted + "-" + n;
        return candidate;
    }

    public static Builder builder(String sessionId, AgentSettings settings, SubagentRuntime.LlmProvider llm,
                                  JeorjeConfig.Implement config) {
        return new Builder(sessionId, settings, llm, config);
    }

    /** Builds an {@link ImplementOrchestrator}. */
    public static final class Builder {
        private final String sessionId;
        private final AgentSettings settings;
        private final SubagentRuntime.LlmProvider llm;
        private final JeorjeConfig.Implement config;
        private ContextStore store;
        private TranscriptSink transcript;
        private LlmClient compactor;
        private WorktreeManager worktrees;
        private Verifier verifier;
        private Reviewer reviewer;
        private Approver approver;
        private RunStateStore stateStore;
        private Clock clock;
        private Duration mergeRetryInterval;
        private Consumer<ImplementEvent> events;
        private Consumer<SubagentEvent> subagentEvents;
        private Consumer<AgentEvent> agentEvents;
        private Consumer<AgentInput> orchestratorInput;
        private SubagentLimits limits;
        private PermissionPolicy policy;
        private org.github.lscoughlin.jeorje.core.subagent.WorkspacePolicies workspacePolicies;
        private List<Tool> baseTools = List.of();
        private WorkerToolsFactory workerTools;
        private org.github.lscoughlin.jeorje.core.subagent.AdvisorLink advisorLink;
        private AgentId agentId;
        private int turnCalls;
        private boolean interactive = true;

        private Builder(String sessionId, AgentSettings settings, SubagentRuntime.LlmProvider llm,
                        JeorjeConfig.Implement config) {
            this.sessionId = sessionId;
            this.settings = settings;
            this.llm = llm;
            this.config = config;
        }

        public Builder store(ContextStore value) { this.store = value; return this; }

        public Builder transcript(TranscriptSink value) { this.transcript = value; return this; }

        public Builder compactor(LlmClient value) { this.compactor = value; return this; }

        /** The worktree manager, or null when the workers share the working directory. */
        public Builder worktrees(WorktreeManager value) { this.worktrees = value; return this; }

        public Builder verifier(Verifier value) { this.verifier = value; return this; }

        /** The reviewer for {@code implement.review: advisor}. Null means that no advisor model is set. */
        public Builder reviewer(Reviewer value) { this.reviewer = value; return this; }

        public Builder approver(Approver value) { this.approver = value; return this; }

        public Builder stateStore(RunStateStore value) { this.stateStore = value; return this; }

        public Builder clock(Clock value) { this.clock = value; return this; }

        /** The time between two merge tries when the parent tree is dirty. The default is 30 seconds. */
        public Builder mergeRetryInterval(Duration value) { this.mergeRetryInterval = value; return this; }

        public Builder events(Consumer<ImplementEvent> value) { this.events = value; return this; }

        public Builder subagentEvents(Consumer<SubagentEvent> value) { this.subagentEvents = value; return this; }

        public Builder agentEvents(Consumer<AgentEvent> value) { this.agentEvents = value; return this; }

        /** Receives the messages and the results for the orchestrator. */
        public Builder orchestratorInput(Consumer<AgentInput> value) { this.orchestratorInput = value; return this; }

        public Builder limits(SubagentLimits value) { this.limits = value; return this; }

        public Builder policy(PermissionPolicy value) { this.policy = value; return this; }

        /** Makes the policy of a worker in a worktree. Without it, a worker uses the policy of the orchestrator. */
        public Builder workspacePolicies(org.github.lscoughlin.jeorje.core.subagent.WorkspacePolicies value) {
            this.workspacePolicies = value;
            return this;
        }

        /** The full coding toolset. The orchestrator removes the tools that a worker never gets. */
        public Builder baseTools(List<Tool> value) { this.baseTools = List.copyOf(value); return this; }

        public Builder workerTools(WorkerToolsFactory value) { this.workerTools = value; return this; }

        /** Turns the turn watch on for the workers: a worker without progress asks the advisor for help. */
        public Builder advisorLink(org.github.lscoughlin.jeorje.core.subagent.AdvisorLink value) {
            this.advisorLink = value;
            return this;
        }

        /**
         * The wanted agent id of the orchestrator. The default is {@code <session id>/orchestrator}. The id of a
         * live orchestrator in the process is never used twice: a second orchestrator gets a numbered id.
         */
        public Builder agentId(AgentId value) { this.agentId = value; return this; }

        /** The largest number of model calls in one turn of the orchestrator. The default is 30. */
        public Builder turnCalls(int value) { this.turnCalls = value; return this; }

        /**
         * True when a user can answer (the default). When false, the orchestrator has no {@code ask_user} tool, its
         * prompt says that no user is present, and the report lists the questions that needed a person.
         */
        public Builder interactive(boolean value) { this.interactive = value; return this; }

        public ImplementOrchestrator build() { return new ImplementOrchestrator(this); }
    }

    // ---- accessors ----

    /** The runtime of the workers. */
    public SubagentRuntime runtime() { return runtime; }

    /** The names of the tools that a worker gets. */
    public List<String> workerToolNames() { return toolNames; }

    /** The story id of a worker agent, for example to label a permission prompt. */
    public Optional<String> storyOf(AgentId agent) {
        var run = byWorker.get(agent);
        return Optional.ofNullable(run == null ? null : run.id);
    }

    /**
     * The agent id of the orchestrator. It is unique in the process. The root node of the runtime and the
     * orchestrator {@link Agent} use it.
     */
    public AgentId agentId() { return agentId; }

    /** The agent of the orchestrator. It has its own history. Its events go to the consumer of {@code agentEvents}. */
    public Agent orchestratorAgent() { return loop.agent(); }

    /** The number of turns that the model loop of the orchestrator started. */
    public int orchestratorTurns() { return loop.turns(); }

    /**
     * Gives a text of the user to the orchestrator. The call only queues the text, or gives it to a running turn.
     * It starts the thread of the orchestrator when needed.
     */
    public void submit(AgentInput input) {
        loop.offer(input);
    }

    /** The messages and results that the orchestrator got. */
    public List<AgentInput> orchestratorMessages() { return List.copyOf(rootMessages); }

    /** Answers a question from a worker that is for the orchestrator. */
    public String answer(String questionId, String text) {
        var result = runtime.answer(agentId, questionId, text);
        return result.ok() ? result.answer() : String.valueOf(result.reason());
    }

    // ---- service API ----

    /**
     * Starts a run. The call returns at once. The future completes with the report when the run ends, or when the
     * run stops before it starts. A paused run keeps the future open.
     */
    public CompletableFuture<ImplementReport> start(ImplementRequest request) {
        var result = new CompletableFuture<ImplementReport>();
        synchronized (lock) {
            if (active || !future.isDone()) throw new IllegalStateException("A run is active. Stop it first.");
            future = result;
        }
        loop.start();
        Thread.ofVirtual().name("implement").start(() -> {
            try {
                begin(request, result);
            } catch (Throwable t) {
                log.atError().setCause(t).log(() -> "The run failed to start");
                synchronized (lock) { active = false; }
                result.complete(ImplementReport.failure(String.valueOf(request.plan()), "error",
                    t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            }
        });
        return result;
    }

    /** Runs a plan and waits for the report. */
    public ImplementReport run(ImplementRequest request) {
        return start(request).join();
    }

    /** Stops starting new stories. Running workers finish. */
    public void pause() {
        synchronized (lock) {
            if (!active) return;
            paused = true;
            emit(new ImplementEvent.RunControl("pause"));
            checkEnd();
        }
    }

    /** Continues a paused run. */
    public void resume() {
        synchronized (lock) {
            if (!active || !paused) return;
            paused = false;
            pausedAnnounced = false;
            persist("running");
            emit(new ImplementEvent.RunControl("resume"));
            pump();
        }
    }

    /** Cancels all workers and ends the run. */
    public void stop() {
        List<String> running = new ArrayList<>();
        synchronized (lock) {
            if (!active) return;
            stopping = true;
            for (var run : runs.values()) if (run.state == StoryState.RUNNING) running.add(run.worker);
            emit(new ImplementEvent.RunControl("stop"));
        }
        for (var worker : running) runtime.cancel(worker);
        synchronized (lock) { checkEnd(); }
    }

    /** True while a run is active, also when it is paused. */
    public boolean active() {
        synchronized (lock) { return active; }
    }

    /** The status line text, for example {@code IMPLEMENT 2/5 (1 blocked)}. Empty when no run is active. */
    public String statusLabel() {
        synchronized (lock) {
            if (!active && runs.isEmpty()) return "";
            var total = selected.size();
            var done = selected.stream().filter(id -> runs.get(id) != null && runs.get(id).state == StoryState.DONE)
                .count();
            var blocked = runs.values().stream().filter(r -> r.state == StoryState.BLOCKED).count();
            return "IMPLEMENT " + done + "/" + total + (blocked > 0 ? " (" + blocked + " blocked)" : "");
        }
    }

    /**
     * The model calls of the running story and the limit of the call count. When more than one worker runs, the
     * worker with the most calls counts. Empty when no worker runs.
     */
    public Optional<SubagentRuntime.ModelCalls> turnUsage() {
        var workers = new ArrayList<String>();
        synchronized (lock) {
            for (var run : runs.values()) {
                if (run.state == StoryState.RUNNING && run.worker != null && !run.worker.isBlank()) {
                    workers.add(run.worker);
                }
            }
        }
        return workers.stream().map(w -> runtime.modelCalls(new AgentId(w))).flatMap(Optional::stream)
            .max(java.util.Comparator.comparingInt(SubagentRuntime.ModelCalls::used));
    }

    /** One row of the status table. */
    public record Row(String story, String title, StoryState state, int attempt, String worker, String branch) {}

    /** The rows of {@code /implement status}. */
    public List<Row> rows() {
        synchronized (lock) {
            var out = new ArrayList<Row>();
            for (var run : runs.values()) {
                out.add(new Row(run.id, run.title, run.state, run.attempt, run.workerName, run.branch));
            }
            return out;
        }
    }

    /** The table of {@code /implement status}. */
    public String status() {
        var rows = rows();
        if (rows.isEmpty()) return "No run.";
        var sb = new StringBuilder(String.format("%-8s %-30s %-9s %-7s %-30s %s%n", "Story", "Title", "State",
            "Attempt", "Worker", "Branch"));
        for (var r : rows) {
            sb.append(String.format("%-8s %-30s %-9s %-7d %-30s %s%n", r.story(), cut(r.title(), 30),
                r.state().label(), r.attempt(), cut(r.worker(), 30), r.branch()));
        }
        return sb.toString().stripTrailing();
    }

    /** The compact view that the orchestrator keeps in its context: id, title, state, one-line result. */
    public String orchestratorContext() {
        synchronized (lock) {
            var sb = new StringBuilder();
            for (var run : runs.values()) {
                sb.append(run.id).append(" | ").append(run.title).append(" | ").append(run.state.label())
                    .append(" | attempt ").append(run.attempt);
                var line = run.state == StoryState.BLOCKED ? run.reason : run.result;
                if (line != null && !line.isBlank()) sb.append(" | ").append(oneLine(line, 160));
                sb.append('\n');
            }
            return sb.toString().stripTrailing();
        }
    }

    // ---- begin ----

    private void begin(ImplementRequest request, CompletableFuture<ImplementReport> result) throws IOException {
        Plan loaded;
        try {
            loaded = PlanParser.parse(request.plan());
        } catch (PlanException | IOException e) {
            result.complete(ImplementReport.failure(request.plan().toString(), "error", e.getMessage()));
            return;
        }
        if (loaded.status().equals("draft")) {
            if (!request.yes() && (approver == null || !approver.approve(loaded))) {
                result.complete(ImplementReport.failure(request.plan().toString(), "not approved",
                    "The plan is a draft and the user did not approve it."));
                return;
            }
            PlanFiles.writeStatus(request.plan(), "approved");
            loaded = PlanFiles.withStatus(loaded, "approved");
        }
        synchronized (lock) {
            plan = loaded;
            planFile = request.plan();
            selected = Scheduler.closure(plan, request.only());
            runs.clear();
            personQuestions.clear();
            byStory.clear();
            byWorker.clear();
            stopping = false;
            paused = false;
            pausedAnnounced = false;
            var old = stateStore.load().filter(s -> s.plan().equals(planFile.toString())).orElse(null);
            for (var story : plan.stories()) {
                var run = new StoryRun(story.id(), story.title());
                runs.put(story.id(), run);
                if (story.done()) {
                    run.state = StoryState.DONE;
                    run.result = "already done";
                    continue;
                }
                var entry = old == null ? null : old.entry(story.id());
                if (entry != null) restore(run, entry);
                if (story.blocked()) {
                    plan = plan.withStory(story.withTasks(story.tasks().stream()
                        .map(t -> t.state() == TaskState.BLOCKED ? t.withState(TaskState.OPEN) : t).toList()));
                }
                runtime.registerPending(story.id());
            }
            writePlanFile();
            plan = PlanFiles.withStatus(plan, "in-progress");
            writeStatus("in-progress");
            active = true;
            var open = (int) selected.stream().filter(id -> runs.get(id).state != StoryState.DONE).count();
            emit(new ImplementEvent.RunStarted(planFile.toString(), plan.stories().size(), open));
            persist("running");
            pump();
        }
    }

    /** Takes the facts of the last run for a story. An interrupted or blocked story starts again. */
    private static void restore(StoryRun run, RunState.Entry entry) {
        run.attempt = entry.attempt();
        switch (entry.state()) {
            case "running" -> {
                run.failures = entry.failures();
                run.previousAttempt = "The previous attempt was interrupted. Jeorje stopped before the worker "
                    + "finished. Check the files in the workspace before you continue.";
            }
            case "blocked" -> {
                run.failures = 0;
                run.previousAttempt = "The story was blocked in the last run: " + entry.reason()
                    + (entry.branch().isBlank() ? "" : "\nThe branch " + entry.branch() + " was kept.");
            }
            default -> {
                run.failures = entry.failures();
                run.previousAttempt = entry.note() == null ? "" : entry.note();
            }
        }
    }

    // ---- scheduling ----

    private Map<String, StoryState> states() {
        var map = new LinkedHashMap<String, StoryState>();
        runs.forEach((id, r) -> map.put(id, r.state));
        return map;
    }

    /** Starts every story that can start. Must hold the lock. */
    private void pump() {
        if (!active) return;
        while (true) {
            propagateBlocked();
            if (paused || stopping) break;
            var next = Scheduler.next(plan, states(), selected, config.parallel());
            if (next.isEmpty()) break;
            for (var story : next) startWorker(story);
        }
        checkEnd();
    }

    private void propagateBlocked() {
        for (var id : Scheduler.blockedDependents(plan, states())) {
            var run = runs.get(id);
            if (run == null || !selected.contains(id)) continue;
            var cause = plan.story(id).map(s -> s.dependsOn().stream()
                .filter(d -> runs.get(d) != null && runs.get(d).state == StoryState.BLOCKED).findFirst().orElse("?"))
                .orElse("?");
            run.state = StoryState.BLOCKED;
            run.reason = "depends on the blocked story " + cause;
            runtime.clearPending(id);
            emit(new ImplementEvent.StoryChanged(id, StoryState.BLOCKED, run.reason));
        }
    }

    /** Ends the run when no worker runs and nothing can start. Must hold the lock. */
    private void checkEnd() {
        if (!active) return;
        var running = runs.values().stream().anyMatch(r -> r.state == StoryState.RUNNING);
        if (running) return;
        if (stopping) {
            finishRun("stopped");
            return;
        }
        if (paused) {
            if (!pausedAnnounced) {
                pausedAnnounced = true;
                persist("paused");
                emit(new ImplementEvent.RunControl("paused"));
            }
            return;
        }
        for (var id : selected) {
            var run = runs.get(id);
            if (run.state == StoryState.PENDING) {
                run.state = StoryState.BLOCKED;
                run.reason = "a dependency is not done and cannot start";
                emit(new ImplementEvent.StoryChanged(id, StoryState.BLOCKED, run.reason));
            }
        }
        finishRun(null);
    }

    // ---- start of a worker ----

    private void startWorker(Story story) {
        var run = runs.get(story.id());
        run.attempt++;
        run.state = StoryState.RUNNING;
        run.resetAttempt();
        run.reason = "";
        var effective = story;
        if (config.handoff() && !story.dependsOn().isEmpty() && (story.handoff() == null || story.handoff().isBlank())) {
            var handoff = handoffText(story);
            if (!handoff.isBlank()) {
                effective = story.withHandoff(handoff);
                plan = plan.withStory(effective);
                writePlanFile();
            }
        }
        var from = startPoint(run);
        var continuing = from != null
            ? "\nThe files of the previous attempt are in your worktree. Keep the work that is correct." : "";
        if (!run.previousAttempt.isBlank()) effective = effective.withPreviousAttempt(run.previousAttempt + continuing);
        if (!run.reviewFeedback.isBlank()) {
            effective = effective.withReviewFeedback("The review of your last attempt listed these problems:\n"
                + run.reviewFeedback + "\nFix these problems. Keep the rest of the work." + continuing);
        }
        var verify = PlanFiles.command(story.verification());
        if (verify.isBlank()) verify = PlanFiles.command(plan.verification());
        run.verify = verify;
        var model = story.model() == null || story.model().isBlank() || story.model().equalsIgnoreCase("default")
            ? (config.model() == null || config.model().isBlank() ? null : config.model()) : story.model();
        var name = story.id() + " " + story.title();
        run.workerName = name;
        persist("running");
        spawnGate.lock();
        try {
            Path predicted = null;
            String wtName = null;
            var group = slug(PlanFiles.slug(planFile));
            if (worktrees != null && canIsolate()) {
                wtName = freeName(group, slug(story.id()), run.attempt);
                predicted = worktrees.projectRoot().resolve(".jeorje").resolve("worktrees").resolve(group)
                    .resolve(wtName);
            }
            var request = new SpawnRequest(name, effective.render() + "\n\n" + WORKER_RULES, toolNames, null, model,
                predicted == null ? "shared" : "worktree", SpawnMode.BACKGROUND, predicted, story.id());
            var id = runtime.spawn(agentId, request);
            runtime.clearPending(story.id());
            run.worker = id.value();
            byWorker.put(id, run);
            byStory.put(story.id(), id);
            if (predicted != null) attachWorktree(run, id, story, group, wtName, predicted, from);
        } catch (RuntimeException e) {
            log.atWarn().setCause(e).log(() -> "Cannot start the worker of " + story.id());
            run.startError = "Cannot start the worker: " + e.getMessage();
            if (run.worker.isBlank()) {
                run.state = StoryState.PENDING;
                failAttempt(run, run.startError, StoryRun.Failure.OTHER);
                return;
            }
            runtime.cancel(run.worker);
        } finally {
            spawnGate.unlock();
        }
        emit(new ImplementEvent.StoryStarted(story.id(), run.attempt, run.worker, run.branch));
        emit(new ImplementEvent.StoryChanged(story.id(), StoryState.RUNNING, "attempt " + run.attempt));
        persist("running");
    }

    /**
     * The worktree of the failed attempt that the next attempt starts from, or null for a start from the source
     * branch. The setting {@code implement.retryFrom} chooses. Must hold the lock.
     */
    private Worktree startPoint(StoryRun run) {
        var kept = run.kept;
        if (kept == null || worktrees == null || !config.retryFrom().equals("previous")) return null;
        var exists = worktrees.git().run(projectRoot, "show-ref", "--verify", "--quiet", "refs/heads/" + kept.branch);
        return exists.ok() ? kept : null;
    }

    private void attachWorktree(StoryRun run, AgentId id, Story story, String group, String name, Path predicted,
                                Worktree from) {
        try {
            var created = worktrees.create(new WorktreeManager.CreateRequest(id, agentId, name, group,
                story.id() + " " + story.title(), projectRoot, SpawnMode.BACKGROUND, "worktree", false, run.verify,
                from));
            if (from != null) {
                emit(new ImplementEvent.Notice("Attempt " + run.attempt + " of " + story.id()
                    + " starts from the branch " + from.branch + " of the failed attempt."));
            }
            if (created.isEmpty()) throw new IllegalStateException("The worktree was not made.");
            var w = created.get();
            if (!w.path.toAbsolutePath().normalize().equals(predicted.toAbsolutePath().normalize())) {
                throw new IllegalStateException("The worktree is at " + w.path + ", not at " + predicted + ".");
            }
            run.worktree = w;
            run.branch = w.branch;
            run.worktreePath = w.path.toString();
            runtime.setBranch(id, w.branch);
        } catch (RuntimeException e) {
            run.startError = "Cannot make the worktree: " + e.getMessage();
            runtime.cancel(id.value());
        }
    }

    private boolean canIsolate() {
        if (worktrees.config().isolation().equals("never")) return false;
        var git = worktrees.git();
        var inside = git.run(projectRoot, "rev-parse", "--is-inside-work-tree");
        if (!inside.ok() || !inside.trimmed().equals("true")) return false;
        return git.run(projectRoot, "symbolic-ref", "--short", "-q", "HEAD").ok();
    }

    /** A worktree name that no directory and no branch uses yet. */
    private String freeName(String group, String base, int attempt) {
        var prefix = worktrees.config().branchPrefix().isBlank() ? "jeorje" : worktrees.config().branchPrefix();
        var n = attempt <= 1 ? 1 : attempt;
        while (true) {
            var name = n == 1 ? base : base + "-a" + n;
            var path = worktrees.projectRoot().resolve(".jeorje").resolve("worktrees").resolve(group).resolve(name);
            var branch = worktrees.git().run(projectRoot, "show-ref", "--verify", "--quiet",
                "refs/heads/" + prefix + "/" + group + "/" + name).ok();
            if (!branch && !java.nio.file.Files.exists(path)) return name;
            n++;
        }
    }

    static String slug(String text) {
        var s = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^[-.]+|[-.]+$", "");
        s = s.replace("..", ".").replaceAll("\\.lock$", "");
        return s.isEmpty() ? "agent" : s.length() > 60 ? s.substring(0, 60) : s;
    }

    /** Short facts from the final summaries of the dependencies. */
    private String handoffText(Story story) {
        var sb = new StringBuilder();
        for (var dep : story.dependsOn()) {
            var run = runs.get(dep);
            if (run == null || run.summary.isBlank()) continue;
            var parts = parts(run.summary);
            sb.append("From ").append(dep).append(" (").append(run.title).append("):\n");
            for (var key : List.of("Files", "Done", "Keywords")) {
                var value = parts.get(key);
                if (value != null && !value.isBlank()) sb.append("- ").append(key).append(": ").append(value).append('\n');
            }
        }
        return sb.toString().strip();
    }

    // ---- the completion sequence ----

    /** The veto of the review. */
    private static final class ReviewRejected extends RuntimeException {
        private final List<String> problems;

        ReviewRejected(List<String> problems) {
            super("Review rejected: " + String.join("; ", problems));
            this.problems = problems;
        }
    }

    private CompletionSteps.Result runSteps(CompletionSteps.Context ctx) throws Exception {
        var run = byWorker.get(ctx.agent());
        if (run == null) return CompletionSteps.Result.skipped();
        if (run.startError != null) return failed(run, StoryRun.Failure.OTHER, run.startError, null);
        for (var nudge = 0; signal(run) == StoryRun.Signal.NONE && nudge < MAX_NUDGES && !ctx.cancel().isCancelled();
             nudge++) {
            ctx.resumeWork("You ended your turn without calling story_done or story_blocked. Finish the tasks of "
                + "your story, run the verification, and call story_done. Call story_blocked if you cannot finish.");
        }
        if (ctx.cancel().isCancelled()) return failed(run, StoryRun.Failure.OTHER, "cancelled", null);
        switch (signal(run)) {
            case NONE -> {
                return failed(run, StoryRun.Failure.OTHER, "The worker ended without story_done or story_blocked.", null);
            }
            case BLOCKED -> {
                return failed(run, StoryRun.Failure.WORKER_BLOCKED, "story_blocked: " + run.signalText, null);
            }
            case DONE -> { }
        }
        var found = worktrees == null ? Optional.<Worktree>empty() : worktrees.byAgent(ctx.agent());
        if (found.isEmpty()) return sharedSequence(run, ctx);
        var w = found.get();
        var completion = new WorktreeCompletion(worktrees, trackedVerifier(ctx.agent()),
                (parent, message) -> note(message))
            .retryInterval(mergeRetryInterval)
            .beforeMerge(round -> review(run, w.path, w.sourceBranch));
        CompletionSteps.Result result;
        try {
            result = completion.run(new TrackingContext(ctx, w, run));
        } catch (ReviewRejected rejected) {
            return failed(run, StoryRun.Failure.REVIEW, rejected.getMessage(), keep(w));
        }
        if (!result.success()) {
            var failure = result.failure() == null ? "The completion steps failed." : result.failure();
            var kind = failure.startsWith("sync_abort:") ? StoryRun.Failure.SYNC_ABORT
                : failure.startsWith("The verification failed") ? StoryRun.Failure.VERIFY : StoryRun.Failure.OTHER;
            return failed(run, kind, failure, result.branch());
        }
        try {
            review(run, w.path, w.sourceBranch);
        } catch (ReviewRejected rejected) {
            return failed(run, StoryRun.Failure.REVIEW, rejected.getMessage(), keep(w));
        }
        if (!w.merged()) run.notMerged = true;
        return result;
    }

    private StoryRun.Signal signal(StoryRun run) {
        synchronized (lock) { return run.signal; }
    }

    private static String keep(Worktree w) {
        return "branch " + w.branch + " and worktree " + w.path + " are kept. The source branch " + w.sourceBranch
            + " is unchanged.";
    }

    /** The verifier of a worker. A passing run is progress, so the worker gets a new budget of model calls. */
    private Verifier trackedVerifier(AgentId worker) {
        return (dir, command) -> {
            var result = verifier.verify(dir, command);
            if (result.ok()) runtime.noteProgress(worker);
            return result;
        };
    }

    private CompletionSteps.Result failed(StoryRun run, StoryRun.Failure kind, String reason, String branch) {
        Worktree tree;
        synchronized (lock) {
            run.failure = kind;
            run.failureText = reason;
            tree = run.worktree;
        }
        // Commit the work now, before the cleanup of the finished worker can remove the directory.
        if (kind != StoryRun.Failure.SYNC_ABORT && tree != null) preserve(tree);
        return CompletionSteps.Result.failed(reason, branch);
    }

    /** The sequence for a worker with no worktree: verification and review. */
    private CompletionSteps.Result sharedSequence(StoryRun run, CompletionSteps.Context ctx) {
        var dir = ctx.workingDirectory();
        for (var attempt = 0; ; attempt++) {
            if (run.verify.isBlank()) break;
            var check = trackedVerifier(ctx.agent()).verify(dir, run.verify);
            if (check.ok()) break;
            if (attempt >= MAX_VERIFY_FIXES) {
                return failed(run, StoryRun.Failure.VERIFY, "The verification failed: " + tail(check.output()), null);
            }
            ctx.resumeWork("The verification command failed.\nCommand: " + run.verify + "\nOutput:\n"
                + tail(check.output()) + "\n\nFix the problem. Then end your turn.");
        }
        try {
            review(run, dir, null);
        } catch (ReviewRejected rejected) {
            return failed(run, StoryRun.Failure.REVIEW, rejected.getMessage(), null);
        }
        return CompletionSteps.Result.ok(null);
    }

    /** Runs the advisor review once for an attempt. A rejection throws {@link ReviewRejected}. */
    private void review(StoryRun run, Path dir, String source) {
        synchronized (lock) {
            if (run.reviewed) return;
        }
        if (reviewer == null || !config.review().equals("advisor")) {
            synchronized (lock) { run.reviewed = true; }
            return;
        }
        Story story;
        synchronized (lock) { story = plan.story(run.id).orElse(null); }
        var text = story == null ? run.title : story.render();
        var files = new ArrayList<String>();
        var diff = "";
        if (worktrees != null && java.nio.file.Files.isDirectory(dir)) {
            var git = worktrees.git();
            var names = git.run(dir, "diff", "--name-only", (source == null ? "HEAD" : source + "...HEAD"));
            if (names.ok()) names.out().lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(files::add);
            var d = git.run(dir, "diff", (source == null ? "HEAD" : source + "...HEAD"));
            if (d.ok()) diff = d.out();
        }
        if (files.isEmpty()) files.addAll(run.files);
        Reviewer.Verdict verdict;
        try {
            verdict = reviewer.review(new Reviewer.Request(run.id, text, run.draft, files, diff));
        } catch (RuntimeException e) {
            log.atWarn().setCause(e).log(() -> "The review of " + run.id + " failed");
            verdict = Reviewer.Verdict.unavailable("The review call failed: " + e.getMessage());
        }
        switch (verdict.kind()) {
            case UNAVAILABLE -> {
                // No answer is not a failed review. The story goes on to the merge back.
                var reason = verdict.reason().isBlank() ? "The review gave no answer." : verdict.reason();
                emit(new ImplementEvent.Notice("The review of " + run.id + " is not available: " + reason
                    + " Jeorje continues without it."));
                synchronized (lock) {
                    run.reviewNote = reason;
                    run.reviewed = true;
                }
            }
            case APPROVED -> {
                emit(new ImplementEvent.Reviewed(run.id, true, ""));
                synchronized (lock) { run.reviewed = true; }
            }
            case REJECTED -> {
                emit(new ImplementEvent.Reviewed(run.id, false, String.join("\n", verdict.problems())));
                synchronized (lock) { run.reviewProblems = verdict.problems(); }
                throw new ReviewRejected(verdict.problems());
            }
        }
    }

    /** Records the conflict files before the sync is aborted, because the abort clears them. */
    private final class TrackingContext implements CompletionSteps.Context {
        private final CompletionSteps.Context delegate;
        private final Worktree worktree;
        private final StoryRun run;

        TrackingContext(CompletionSteps.Context delegate, Worktree worktree, StoryRun run) {
            this.delegate = delegate;
            this.worktree = worktree;
            this.run = run;
        }

        @Override public AgentId agent() { return delegate.agent(); }

        @Override public String name() { return delegate.name(); }

        @Override public Path workingDirectory() { return delegate.workingDirectory(); }

        @Override public String isolation() { return delegate.isolation(); }

        @Override public CancellationToken cancel() { return delegate.cancel(); }

        @Override public void enter(org.github.lscoughlin.jeorje.core.subagent.AgentLifecycle phase) {
            delegate.enter(phase);
        }

        @Override public AutoCloseable internalWait(AgentId holder, String reason) {
            return delegate.internalWait(holder, reason);
        }

        @Override
        public void resumeWork(String message) {
            delegate.resumeWork(message);
            recordConflicts();
        }

        @Override
        public int resumeWork(String message, int maxModelCalls) {
            var used = delegate.resumeWork(message, maxModelCalls);
            recordConflicts();
            return used;
        }

        private void recordConflicts() {
            try {
                var files = worktrees.unmerged(worktree);
                if (!files.isEmpty()) {
                    synchronized (lock) { run.conflicts = new ArrayList<>(files); }
                }
            } catch (RuntimeException e) {
                log.atDebug().log(() -> "Cannot read the conflicts: " + e.getMessage());
            }
        }
    }

    // ---- the end of a worker ----

    private void onSubagentEvent(SubagentEvent event) {
        try {
            subagentEvents.accept(event);
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "An event consumer failed: " + e.getMessage());
        }
        if (!(event instanceof SubagentEvent.Finished finished)) return;
        if (finisher != null) finisher.accept(event);
        var run = byWorker.get(finished.agent());
        if (run == null) return;
        synchronized (lock) {
            if (run.state != StoryState.RUNNING) return;
            try {
                onWorkerFinished(run, finished.completion());
            } catch (RuntimeException e) {
                log.atError().setCause(e).log(() -> "Cannot handle the end of " + run.id);
                run.state = StoryState.BLOCKED;
                run.reason = "Internal error: " + e.getMessage();
                releaseKept(run);
            }
            persist(stopping ? "stopped" : paused ? "paused" : "running");
            pump();
        }
    }

    private void onWorkerFinished(StoryRun run, Completion completion) {
        if (stopping && completion.outcome() == Completion.Outcome.CANCELLED) {
            run.state = StoryState.PENDING;
            run.previousAttempt = "The previous attempt was stopped before it finished.";
            releaseKept(run);
            discardWorktree(run);
            emit(new ImplementEvent.StoryChanged(run.id, StoryState.PENDING, "stopped"));
            return;
        }
        if (completion.outcome() == Completion.Outcome.COMPLETED && run.startError == null) {
            if (run.notMerged) {
                block(run, "completed (not merged): " + oneLine(completion.summary(), 400));
                return;
            }
            succeed(run, completion.summary());
            return;
        }
        var reason = run.failureText.isBlank()
            ? (completion.reason() == null ? "The worker failed." : completion.reason()) : run.failureText;
        if (run.startError != null) reason = run.startError;
        failAttempt(run, reason, run.failure == StoryRun.Failure.NONE ? StoryRun.Failure.OTHER : run.failure);
    }

    private void succeed(StoryRun run, String summary) {
        releaseKept(run);
        run.state = StoryState.DONE;
        run.summary = summary;
        var parts = parts(summary);
        run.result = oneLine(parts.getOrDefault("Done", summary), 200)
            + (run.reviewNote.isBlank() ? "" : " (The review gave no answer.)");
        var files = parts.get("Files");
        if (files != null && !files.isBlank() && !files.strip().equalsIgnoreCase("none")) {
            var list = new ArrayList<String>();
            for (var f : files.split(",")) if (!f.isBlank()) list.add(f.strip());
            if (!list.isEmpty()) run.files = list;
        }
        markTasks(run.id, TaskState.DONE);
        run.previousAttempt = "";
        run.reviewFeedback = "";
        if (run.repairOf != null) {
            var parent = runs.get(run.repairOf);
            if (parent != null) {
                parent.state = StoryState.DONE;
                parent.result = "repaired by " + run.id + ": " + run.result;
                parent.reason = "";
                markTasks(parent.id, TaskState.DONE);
                dropWorktree(parent);
                emit(new ImplementEvent.StoryChanged(parent.id, StoryState.DONE, parent.result));
            }
        }
        emit(new ImplementEvent.StoryChanged(run.id, StoryState.DONE, run.result));
    }

    private void failAttempt(StoryRun run, String reason, StoryRun.Failure kind) {
        run.failure = kind;
        if (kind == StoryRun.Failure.SYNC_ABORT) {
            block(run, reason + (run.conflicts.isEmpty() ? "" : " Conflicting files: " + String.join(", ", run.conflicts)));
            return;
        }
        run.failures++;
        var text = "Attempt " + run.attempt + " failed.\n"
            + (kind == StoryRun.Failure.VERIFY && !run.verify.isBlank() ? "Failing check: " + run.verify + "\n" : "")
            + "Reason: " + tail(reason);
        if (run.failures < config.maxAttempts()) {
            retire(run);
            markTasks(run.id, TaskState.OPEN);
            run.previousAttempt = text;
            run.reviewFeedback = kind == StoryRun.Failure.REVIEW
                ? run.reviewProblems.stream().map(p -> "- " + p).collect(java.util.stream.Collectors.joining("\n")) : "";
            run.state = StoryState.PENDING;
            runtime.registerPending(run.id);
            emit(new ImplementEvent.StoryChanged(run.id, StoryState.PENDING, "retry after: " + oneLine(reason, 200)));
            return;
        }
        run.previousAttempt = text;
        block(run, reason);
    }

    /**
     * Ends the worktree of a failed attempt that gets a retry. With {@code implement.retryFrom: previous}, the
     * worktree stays when it has work, and the next attempt starts from its branch. Jeorje removes the worktree of
     * the attempt before it, because the new branch has its commits. Otherwise the worktree goes away.
     */
    private void retire(StoryRun run) {
        var current = run.worktree;
        if (current == null) return;
        run.worktree = null;
        var keep = false;
        if (worktrees != null && config.retryFrom().equals("previous")) {
            preserve(current);
            try {
                keep = worktrees.aheadOfSource(current) > 0;
            } catch (RuntimeException e) {
                log.atWarn().log(() -> "Cannot check the work in " + current.path + ": " + e.getMessage());
            }
        }
        releaseKept(run);
        if (keep) run.kept = current;
        else discardTree(current);
    }

    /** Commits the files of a failed attempt, so that the branch has the work. A sync with open conflicts stays. */
    private void preserve(Worktree w) {
        if (worktrees == null || !config.retryFrom().equals("previous") || !java.nio.file.Files.isDirectory(w.path)) return;
        try {
            if (worktrees.unmerged(w).isEmpty()) worktrees.commitRemaining(w);
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "Cannot commit the work in " + w.path + ": " + e.getMessage());
        }
    }

    /** Removes the worktree and the branch of the attempt before the last one. */
    private void releaseKept(StoryRun run) {
        var old = run.kept;
        run.kept = null;
        if (old != null) discardTree(old);
    }

    private void block(StoryRun run, String reason) {
        releaseKept(run);
        run.state = StoryState.BLOCKED;
        run.reason = reason;
        run.result = "";
        plan.story(run.id).ifPresent(story -> story.tasks().stream().filter(t -> t.state() == TaskState.OPEN)
            .findFirst().ifPresent(t -> {
                plan = plan.withStory(story.withTaskState(t.id(), TaskState.BLOCKED));
                writePlanFile();
            }));
        emit(new ImplementEvent.StoryChanged(run.id, StoryState.BLOCKED, reason));
    }

    private void discardWorktree(StoryRun run) {
        if (run.worktree != null) dropWorktree(run);
    }

    private void dropWorktree(StoryRun run) {
        var w = run.worktree;
        run.worktree = null;
        discardTree(w);
    }

    private void discardTree(Worktree w) {
        if (w == null || worktrees == null) return;
        try {
            worktrees.discard(w);
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "Cannot discard " + w.path + ": " + e.getMessage());
        }
    }

    private void markTasks(String storyId, TaskState state) {
        var story = plan.story(storyId).orElse(null);
        if (story == null) return;
        plan = plan.withStory(story.withTasks(story.tasks().stream().map(t -> t.withState(state)).toList()));
        writePlanFile();
    }

    // ---- the end of a run ----

    /** Ends the run. Must hold the lock. The plan verification runs on another thread. */
    private void finishRun(String forced) {
        active = false;
        var snapshot = runs.values().stream().map(this::resultOf).toList();
        var blocked = runs.values().stream().anyMatch(r -> r.state == StoryState.BLOCKED);
        var allDone = plan.stories().stream().allMatch(s -> runs.get(s.id()).state == StoryState.DONE);
        var fut = future;
        var file = planFile;
        var questions = List.copyOf(personQuestions);
        var assumptions = assumptionsOf();
        var command = PlanFiles.command(plan.verification());
        Thread.ofVirtual().name("implement-finish").start(() -> {
            String status;
            var verification = "skipped";
            var output = "";
            if (forced != null) {
                status = forced;
            } else if (blocked) {
                status = "blocked";
            } else {
                status = "done";
                if (allDone && !command.isBlank()) {
                    var check = verifier.verify(projectRoot, command);
                    verification = check.ok() ? "passed" : "failed";
                    output = check.ok() ? "" : tail(check.output());
                    if (!check.ok()) status = "blocked";
                }
            }
            synchronized (lock) {
                try {
                    var planStatus = forced != null ? "in-progress" : status.equals("blocked") ? "blocked"
                        : allDone ? "done" : "in-progress";
                    plan = PlanFiles.withStatus(plan, planStatus);
                    PlanFiles.writeStatus(file, planStatus);
                } catch (IOException e) {
                    log.atWarn().log(() -> "Cannot write the plan status: " + e.getMessage());
                }
                if (forced != null) cleanupKept();
                persist(status);
            }
            var report = new ImplementReport(file.toString(), status, snapshot, verification, output, null,
                questions, assumptions);
            emit(new ImplementEvent.RunFinished(report));
            fut.complete(report);
        });
    }

    /** The decisions that workers stated in the line {@code Decisions} of their summaries. Must hold the lock. */
    private List<String> assumptionsOf() {
        var out = new ArrayList<String>();
        for (var run : runs.values()) {
            if (run.summary.isBlank()) continue;
            var decisions = parts(run.summary).get("Decisions");
            if (decisions == null || decisions.isBlank()) continue;
            if (decisions.strip().toLowerCase(Locale.ROOT).startsWith("none")) continue;
            out.add(run.id + ": " + oneLine(decisions, 300));
        }
        return out;
    }

    /** After a stop: removes the worktrees that stay from blocked stories. */
    private void cleanupKept() {
        for (var run : runs.values()) {
            releaseKept(run);
            if (run.worktree != null) dropWorktree(run);
        }
    }

    private ImplementReport.StoryResult resultOf(StoryRun run) {
        return new ImplementReport.StoryResult(run.id, run.title, run.state, run.attempt, run.result,
            List.copyOf(run.files), run.reason);
    }

    // ---- repair ----

    /**
     * Starts a repair story for a story that ended with {@code sync_abort}. A new worker gets the conflict, the text
     * of the blocked story, and the text of the stories that touch the same files. It merges the kept branch into
     * a fresh worktree and resolves the conflict.
     */
    public CompletableFuture<ImplementReport> repair(String storyId) {
        var result = new CompletableFuture<ImplementReport>();
        synchronized (lock) {
            if (active) throw new IllegalStateException("A run is active. Wait until it ends.");
            var run = runs.get(storyId);
            var story = plan == null ? null : plan.story(storyId).orElse(null);
            if (run == null || story == null || run.state != StoryState.BLOCKED || run.branch.isBlank()) {
                throw new IllegalStateException("The story " + storyId + " is not blocked with a kept branch.");
            }
            var repairId = storyId + "-repair";
            var related = new StringBuilder();
            for (var other : plan.stories()) {
                if (other.id().equals(storyId)) continue;
                var r = runs.get(other.id());
                var shares = Scheduler.touchesOverlap(story.touches(), other.touches())
                    || run.conflicts.stream().anyMatch(f -> r != null && r.summary.contains(f));
                if (shares && r != null && r.state == StoryState.DONE) {
                    related.append("\n#### Related story ").append(other.id()).append("\n").append(other.render()).append('\n');
                }
            }
            var context = "The story " + storyId + " could not merge. The worker gave up with this reason:\n"
                + run.reason + "\n\nConflicting files: " + String.join(", ", run.conflicts) + "\nThe branch "
                + run.branch + " is kept. It has the finished work of " + storyId + ".\n\nThe blocked story:\n"
                + story.render() + "\n" + related;
            var repair = new Story(repairId, "Repair " + story.title(),
                "Merge the branch " + run.branch + " into your worktree and resolve the conflicts.",
                story.dependsOn(), story.touches(), story.model(), context.strip(),
                "- No conflict markers remain.\n- The work of " + storyId + " and the work that is already merged both work.",
                story.verification(), List.of(
                    new Task(repairId + ".T1", "Run `git merge " + run.branch + "` in your worktree."),
                    new Task(repairId + ".T2", "Resolve the conflicts so that both behaviors stay."),
                    new Task(repairId + ".T3", "Run the verification of the story.")));
            var stories = new ArrayList<>(plan.stories());
            stories.add(repair);
            plan = new Plan(plan.front(), plan.frontLines(), plan.preamble(), stories, plan.epilogue());
            var repairRun = new StoryRun(repairId, repair.title());
            repairRun.repairOf = storyId;
            runs.put(repairId, repairRun);
            var widened = new LinkedHashSet<>(selected);
            widened.add(repairId);
            selected = widened;
            for (var other : runs.values()) {
                if (other.state == StoryState.BLOCKED && other.reason.startsWith("depends on the blocked story")) {
                    other.state = StoryState.PENDING;
                    other.reason = "";
                }
            }
            future = result;
            active = true;
            stopping = false;
            paused = false;
            pausedAnnounced = false;
            runtime.registerPending(repairId);
            emit(new ImplementEvent.Notice("Repair story " + repairId + " starts for " + storyId + "."));
            persist("running");
            pump();
        }
        return result;
    }

    // ---- StoryHost ----

    private StoryRun runOf(AgentId worker) {
        var run = byWorker.get(worker);
        if (run == null || run.state != StoryState.RUNNING || !run.worker.equals(worker.value())) return null;
        return run;
    }

    @Override
    public Reply taskDone(AgentId worker, String taskId, String note) {
        var reply = markTaskDone(worker, taskId, note);
        // A finished task is progress. The worker gets a new budget of model calls.
        if (reply.ok()) runtime.noteProgress(worker);
        return reply;
    }

    private Reply markTaskDone(AgentId worker, String taskId, String note) {
        synchronized (lock) {
            var run = runOf(worker);
            if (run == null) return Reply.error("You have no running story.");
            var story = plan.story(run.id).orElse(null);
            if (story == null) return Reply.error("The story is not in the plan.");
            var task = story.tasks().stream().filter(t -> t.id().equals(taskId)).findFirst().orElse(null);
            if (task == null) {
                return Reply.error("The story " + run.id + " has no task " + taskId + ". Its tasks: "
                    + String.join(", ", story.tasks().stream().map(Task::id).toList()) + ".");
            }
            if (task.state() != TaskState.DONE) {
                plan = plan.withStory(story.withTaskState(taskId, TaskState.DONE));
                writePlanFile();
            }
            emit(new ImplementEvent.TaskFinished(run.id, taskId, note == null ? "" : note));
            return Reply.ok("Task " + taskId + " is marked done in the plan.");
        }
    }

    @Override
    public Reply storyDone(AgentId worker, String summary, List<String> filesChanged) {
        synchronized (lock) {
            var run = runOf(worker);
            if (run == null) return Reply.error("You have no running story.");
            if (run.signal != StoryRun.Signal.NONE) return Reply.error("You already ended the story.");
            if (summary == null || summary.isBlank()) return Reply.error("Give a summary of your work.");
            var story = plan.story(run.id).orElse(null);
            if (story != null) {
                var open = story.tasks().stream().filter(t -> t.state() != TaskState.DONE).map(Task::id).toList();
                if (!open.isEmpty()) {
                    return Reply.error("These tasks are not done: " + String.join(", ", open)
                        + ". Call task_done for each task first, or story_blocked if you cannot finish.");
                }
            }
            run.draft = summary;
            run.files = filesChanged == null ? new ArrayList<>() : new ArrayList<>(filesChanged);
            run.signal = StoryRun.Signal.DONE;
            return Reply.ok("The story is recorded as done. End your turn now. Jeorje merges your branch from the "
                + "source, runs the verification, asks for the review, and merges back. It may send you a "
                + "message if the sync has a conflict.");
        }
    }

    @Override
    public Reply storyBlocked(AgentId worker, String reason) {
        synchronized (lock) {
            var run = runOf(worker);
            if (run == null) return Reply.error("You have no running story.");
            if (run.signal != StoryRun.Signal.NONE) return Reply.error("You already ended the story.");
            if (reason == null || reason.isBlank()) return Reply.error("Give a reason.");
            run.signal = StoryRun.Signal.BLOCKED;
            run.signalText = reason;
            return Reply.ok("The story is recorded as blocked. End your turn now.");
        }
    }

    @Override
    public String storyList() {
        synchronized (lock) {
            if (plan == null) return "No plan is loaded.";
            var sb = new StringBuilder();
            for (var story : plan.stories()) {
                var run = runs.get(story.id());
                sb.append(story.id()).append(" | ").append(story.title()).append(" | ")
                    .append(run == null ? "pending" : run.state.label()).append(" | attempt ")
                    .append(run == null ? 0 : run.attempt);
                if (run != null && !run.workerName.isBlank() && run.state == StoryState.RUNNING) {
                    sb.append(" | worker ").append(run.workerName);
                }
                sb.append('\n');
            }
            return sb.toString().stripTrailing();
        }
    }

    @Override
    public Optional<String> storyText(String storyId) {
        synchronized (lock) {
            if (plan == null) return Optional.empty();
            return plan.story(storyId).map(story -> {
                var run = runs.get(storyId);
                return "State: " + (run == null ? "pending" : run.state.label())
                    + (run != null && run.attempt > 0 ? ", attempt " + run.attempt : "")
                    + (run != null && !run.workerName.isBlank() && run.state == StoryState.RUNNING
                        ? ", worker " + run.workerName : "") + "\n\n" + story.render();
            });
        }
    }

    // ---- helpers ----

    private void writePlanFile() {
        try {
            PlanWriter.updateFile(planFile, plan);
        } catch (IOException e) {
            log.atWarn().log(() -> "Cannot write the plan: " + e.getMessage());
            emit(new ImplementEvent.Notice("Cannot write the plan file: " + e.getMessage()));
        }
    }

    private void writeStatus(String status) {
        try {
            PlanFiles.writeStatus(planFile, status);
        } catch (IOException e) {
            log.atWarn().log(() -> "Cannot write the plan status: " + e.getMessage());
        }
    }

    private void persist(String status) {
        try {
            var entries = new ArrayList<RunState.Entry>();
            for (var run : runs.values()) entries.add(run.entry());
            stateStore.save(new RunState(planFile == null ? "" : planFile.toString(), status, entries));
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "Cannot save the run state: " + e.getMessage());
        }
    }

    private void emit(ImplementEvent event) {
        try {
            events.accept(event);
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "An event consumer failed: " + e.getMessage());
        }
    }

    private static final Pattern QUESTION_HEAD =
        Pattern.compile("^\\[question from [^\\n]*?\\((.*?)\\), id q\\d+\\]\\s*(.*)$", Pattern.DOTALL);

    /**
     * Wraps {@code agent_answer}. An answer with {@code needsPerson} set to true adds the question and the answer to
     * the list that the report shows.
     */
    private final class AnswerRecorder implements Tool {
        private final Tool inner;

        AnswerRecorder(Tool inner) { this.inner = inner; }

        @Override public org.github.lscoughlin.jeorje.core.model.ToolDefinition definition() { return inner.definition(); }

        @Override public boolean readOnly() { return inner.readOnly(); }

        @Override public Optional<String> check(tools.jackson.databind.JsonNode input) { return inner.check(input); }

        @Override
        public org.github.lscoughlin.jeorje.core.model.ToolResultData execute(tools.jackson.databind.JsonNode input,
                org.github.lscoughlin.jeorje.core.port.ToolContext context) throws Exception {
            var result = inner.execute(input, context);
            if (!result.error() && input.path("needsPerson").asBoolean(false)) {
                var text = loop.questionText(input.path("questionId").asString(""));
                var head = text == null ? null : QUESTION_HEAD.matcher(text);
                var who = head != null && head.matches() ? head.group(1) : "a worker";
                var question = head != null && head.matches() ? head.group(2) : String.valueOf(text);
                personQuestions.add(who + ": " + oneLine(question, 300) + " Answer: "
                    + oneLine(input.path("text").asString(""), 300));
            }
            return result;
        }
    }

    private void onRootInput(AgentInput input) {
        rootMessages.add(input);
        if (orchestratorInput != null) {
            try {
                orchestratorInput.accept(input);
            } catch (RuntimeException e) {
                log.atWarn().log(() -> "Cannot deliver a message to the orchestrator: " + e.getMessage());
            }
        }
        // Only the queue takes the input. The asker waits on this thread, so no turn can run here.
        loop.offer(input);
    }

    /** Writes a note in the history of the orchestrator. */
    private void note(String text) {
        emit(new ImplementEvent.Notice(text));
        if (store == null) return;
        var part = 2000 + noteCounter.incrementAndGet();
        try {
            store.append(List.of(new Chunk(agentId + ":1." + part, sessionId, agentId.value(), 1, part, clock.instant(),
                "note", null, text)));
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "Cannot store a note: " + e.getMessage());
        }
    }

    /** The labeled parts of a completion summary. */
    static Map<String, String> parts(String summary) {
        var out = new LinkedHashMap<String, String>();
        for (var line : summary.split("\n")) {
            var m = LABEL.matcher(line.strip());
            if (m.matches()) out.putIfAbsent(m.group(1), m.group(2).strip());
        }
        return out;
    }

    private static String oneLine(String text, int max) {
        var s = text.replaceAll("\\s+", " ").strip();
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max - 1) + "~" : text;
    }

    private static String tail(String text) {
        return text.length() > OUTPUT_TAIL ? "..." + text.substring(text.length() - OUTPUT_TAIL) : text;
    }

    @Override
    public void close() {
        rootToken.cancel();
        loop.close();
        runtime.close();
        USED_IDS.remove(agentId.value());
    }
}
