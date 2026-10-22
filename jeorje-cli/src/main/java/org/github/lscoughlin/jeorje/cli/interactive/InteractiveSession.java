package org.github.lscoughlin.jeorje.cli.interactive;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.github.lscoughlin.jeorje.cli.CliException;
import org.github.lscoughlin.jeorje.cli.CliEnvironment;
import org.github.lscoughlin.jeorje.cli.GlobalOptions;
import org.github.lscoughlin.jeorje.cli.AdvisorSupport;
import org.github.lscoughlin.jeorje.cli.PeerSpawnTool;
import org.github.lscoughlin.jeorje.cli.ProjectRuleWriter;
import org.github.lscoughlin.jeorje.cli.SwarmBridge;
import org.github.lscoughlin.jeorje.cli.Wiring;
import org.github.lscoughlin.jeorje.core.agent.Agent;
import org.github.lscoughlin.jeorje.core.agent.AgentInput;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.compaction.CompactionSettings;
import org.github.lscoughlin.jeorje.core.compaction.ContextClassifier;
import org.github.lscoughlin.jeorje.core.compaction.ContextCompactor;
import org.github.lscoughlin.jeorje.core.compaction.ContextEntry;
import org.github.lscoughlin.jeorje.core.compaction.Protection;
import org.github.lscoughlin.jeorje.core.compaction.ModelRoles;
import org.github.lscoughlin.jeorje.core.compaction.Pruner;
import org.github.lscoughlin.jeorje.core.compaction.TermStats;
import org.github.lscoughlin.jeorje.core.config.ApiKeyResolver;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.config.LoadedConfig;
import org.github.lscoughlin.jeorje.core.config.SecretScrubber;
import org.github.lscoughlin.jeorje.core.implement.AdvisorReviewer;
import org.github.lscoughlin.jeorje.core.implement.ImplementEvent;
import org.github.lscoughlin.jeorje.core.implement.ImplementOrchestrator;
import org.github.lscoughlin.jeorje.core.implement.RunStateStore;
import org.github.lscoughlin.jeorje.core.instructions.AgentTypeRegistry;
import org.github.lscoughlin.jeorje.core.instructions.CommandRegistry;
import org.github.lscoughlin.jeorje.core.instructions.InstructionsLoader;
import org.github.lscoughlin.jeorje.core.instructions.Scopes;
import org.github.lscoughlin.jeorje.core.permissions.SkillGrantPolicy;
import org.github.lscoughlin.jeorje.core.instructions.SkillRegistry;
import org.github.lscoughlin.jeorje.core.instructions.TrustStore;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.permissions.AuditLog;
import org.github.lscoughlin.jeorje.core.permissions.Rule;
import org.github.lscoughlin.jeorje.core.permissions.ToolPermissionPolicy;
import org.github.lscoughlin.jeorje.core.plan.PlanMode;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.Embedder;
import org.github.lscoughlin.jeorje.core.port.Filters;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.Syntax;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.session.Session;
import org.github.lscoughlin.jeorje.core.session.SessionManifest;
import org.github.lscoughlin.jeorje.core.session.SessionStore;
import org.github.lscoughlin.jeorje.core.session.TranscriptReader;
import org.github.lscoughlin.jeorje.core.session.ScrubbingContextStore;
import org.github.lscoughlin.jeorje.core.session.TranscriptWriter;
import org.github.lscoughlin.jeorje.core.session.TurnChunker;
import org.github.lscoughlin.jeorje.core.state.AgentState;
import org.github.lscoughlin.jeorje.core.subagent.CompletionSteps;
import org.github.lscoughlin.jeorje.core.subagent.SubagentLimits;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.core.worktree.GitRunner;
import org.github.lscoughlin.jeorje.core.worktree.Verifier;
import org.github.lscoughlin.jeorje.core.worktree.WorktreeCompletion;
import org.github.lscoughlin.jeorje.core.worktree.WorktreeFinisher;
import org.github.lscoughlin.jeorje.core.worktree.WorktreeManager;
import org.github.lscoughlin.jeorje.index.store.LuceneContextStore;
import org.github.lscoughlin.jeorje.swarm.PeerState;
import org.github.lscoughlin.jeorje.swarm.SwarmNode;
import org.github.lscoughlin.jeorje.tools.agents.AgentTools;
import org.github.lscoughlin.jeorje.tools.memory.AdvisorTool;
import org.github.lscoughlin.jeorje.tools.memory.AskUserTool;
import org.github.lscoughlin.jeorje.tools.memory.UserQuestion;
import org.github.lscoughlin.jeorje.tools.memory.UserQuestionBroker;
import org.github.lscoughlin.jeorje.tools.plan.PlanSubmitTool;
import org.github.lscoughlin.jeorje.tools.shell.BashTool;
import org.github.lscoughlin.jeorje.tools.shell.ShellRunner;
import org.github.lscoughlin.jeorje.tools.shell.ShellSettings;
import org.github.lscoughlin.jeorje.tools.registry.ToolRegistry;
import org.github.lscoughlin.jeorje.tools.skills.SkillTool;
import org.github.lscoughlin.jeorje.tools.story.StoryTools;
import org.github.lscoughlin.jeorje.tools.worktree.WorktreeCommands;
import org.github.lscoughlin.jeorje.ui.commands.SlashCommands;
import org.github.lscoughlin.jeorje.ui.mouse.MouseConfig;
import org.github.lscoughlin.jeorje.ui.mouse.MouseInput;
import org.github.lscoughlin.jeorje.ui.mouse.RegionRegistry;
import org.github.lscoughlin.jeorje.ui.browser.FileBrowser;
import org.github.lscoughlin.jeorje.ui.browser.FileBrowserPane;
import org.github.lscoughlin.jeorje.ui.editor.QEditor;
import org.github.lscoughlin.jeorje.ui.screen.ActionHandler;
import org.github.lscoughlin.jeorje.ui.screen.Decision;
import org.github.lscoughlin.jeorje.ui.switcher.AgentSwitcherPane;
import org.github.lscoughlin.jeorje.ui.screen.FormPrompt;
import org.github.lscoughlin.jeorje.ui.screen.Screen;
import org.github.lscoughlin.jeorje.ui.switcher.AgentSwitcher;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One interactive session. It builds the screen, the agent of the user, the subagent runtime, the
 * {@code /implement} orchestrator, the worktree manager, the swarm node, the mouse input, and the slash commands.
 * It then runs until the user exits.
 *
 * <p>Threads. The input thread of the {@link Screen} calls {@link #onSubmit}. That thread also answers the
 * permission prompts, so no handler runs a turn or a run on it. The only exception is the editor, which has to own
 * the keyboard. {@link #run()} runs on the calling thread and does the teardown on that thread.
 */
public final class InteractiveSession implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(InteractiveSession.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The id of the first conversation. It is the same value as {@link MainSession#MAIN}. */
    static final AgentId MAIN = MainSession.MAIN;
    private static final Duration BANG_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Values that a test can set.
     *
     * @param historyFile the input history file, or null for the default file in the home directory
     * @param mergeRetryInterval the time between two merge tries in {@code /implement}, or null for the default
     * @param interruptWindow the time in which a second {@code Ctrl-C} exits
     */
    public record Hooks(Path historyFile, Duration mergeRetryInterval, Duration interruptWindow) {
        public Hooks {
            interruptWindow = interruptWindow == null ? Duration.ofSeconds(2) : interruptWindow;
        }

        public static Hooks defaults() { return new Hooks(null, null, null); }
    }

    // ---- inputs ----
    private final CliEnvironment env;
    private final GlobalOptions options;
    private final Terminal realTerminal;
    private final Hooks hooks;
    private final String resume;

    // ---- state ----
    private final CountDownLatch quit = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final MainSession main = MainSession.first();
    private final Map<String, LlmClient> clients = new ConcurrentHashMap<>();
    private final List<String> attachments = new CopyOnWriteArrayList<>();
    private final AtomicInteger promptIds = new AtomicInteger();
    private final List<String> startupNotices = new ArrayList<>();
    private volatile ModelRoles roles;
    private volatile String modelOverride;
    private volatile boolean promptSent;
    private volatile long lastInterrupt;
    private volatile boolean trusted;
    private volatile SkillRegistry skills = SkillRegistry.scan(List.of());
    private volatile CommandRegistry commandRegistry = CommandRegistry.scan(List.of(), java.util.Set.of());
    private volatile AgentTypeRegistry agentTypes = AgentTypeRegistry.scan(List.of());
    private volatile String cachedBranch = "";
    private volatile long branchCheckedAt;
    private volatile String lastStatus = "";
    private volatile boolean uiStarted;
    private volatile org.github.lscoughlin.jeorje.ui.keys.KeyBindings keys;
    private volatile int window;
    private AdvisorSupport advisor;
    private Pruner pruner;
    private ContextCompactor normalCompactor;

    private LoadedConfig loaded;
    private JeorjeConfig config;
    private Path cwd;
    private Path home;
    private SessionStore sessions;
    private ScrubbingContextStore countingStore;
    private Embedder embedder;
    private SecretScrubber scrubber;
    private ToolPermissionPolicy policy;
    private Screen screen;
    private PromptGate gate;
    private ImplementOrchestrator orchestrator;
    private WorktreeManager worktrees;
    private WorktreeCommands worktreeCommands;
    private WorktreeCompletion completion;
    private WorktreeFinisher finisher;
    private volatile AgentState toolState;
    private volatile SkillGrantPolicy skillGrants;
    private volatile List<org.github.lscoughlin.jeorje.core.instructions.Skill> toolSkills = List.of();
    private volatile SkillTool skillTool;
    private org.github.lscoughlin.jeorje.tools.mcp.McpManager mcp;
    private boolean mcpStarted;
    private volatile QEditor activeEditor;
    private final Map<String, String> startedTools = new ConcurrentHashMap<>();
    private boolean screenStarted;
    private GitRunner git;
    private SwarmNode swarm;
    private SwarmBridge bridge;
    private int resumedTurns = -1;
    private MouseInput mouse;
    private RegionRegistry registry;
    private org.github.lscoughlin.jeorje.ui.mouse.Clipboard clipboard;
    private org.github.lscoughlin.jeorje.ui.mouse.ChatMouse chatMouse;
    private SlashCommands slash;
    private AgentSwitcher switcher;
    private AgentRoster roster;
    private UserQuestionBroker broker;
    private Thread ticker;
    private SessionCommands commands;

    public InteractiveSession(CliEnvironment env, GlobalOptions options, Terminal terminal, Hooks hooks) {
        this(env, options, terminal, hooks, null);
    }

    /**
     * @param resume the id of the session to resume, an empty string for the latest session of the project, or
     *               null to start a new session
     */
    public InteractiveSession(CliEnvironment env, GlobalOptions options, Terminal terminal, Hooks hooks,
                              String resume) {
        this.resume = resume;
        this.env = env;
        this.options = options;
        this.realTerminal = terminal;
        this.hooks = hooks == null ? Hooks.defaults() : hooks;
    }

    // ---- accessors ----

    public Screen screen() { return screen; }

    /** The conversation that the interface works in. The first session has the id {@code main}. */
    public MainSession main() { return main; }

    public Agent agent() { return main.agent(); }

    public ImplementOrchestrator orchestrator() { return orchestrator; }

    public WorktreeManager worktrees() { return worktrees; }

    public PlanMode planMode() { return main.planMode(); }

    public SwarmNode swarm() { return swarm; }

    public Session session() { return main.session(); }

    Path cwd() { return cwd; }

    JeorjeConfig config() { return config; }

    GlobalOptions options() { return options; }

    SlashCommands slash() { return slash; }

    AgentRoster roster() { return roster; }

    AgentSwitcher switcher() { return switcher; }

    PromptGate gate() { return gate; }

    Terminal realTerminal() { return realTerminal; }

    MouseInput mouse() { return mouse; }

    WorktreeCommands worktreeCommands() { return worktreeCommands; }

    List<String> attachments() { return attachments; }

    Path planDir() { return cwd.resolve(config.plan().dir()).normalize(); }

    // ---- run ----

    /** Runs the session until the user exits. Returns the exit code. */
    public int run() {
        try {
            open();
            if (quit.getCount() > 0) startUi();
            quit.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            shutdown();
        }
        printKeptWorktrees();
        return 0;
    }

    /** Asks the session to end. Any thread can call it. */
    public void requestExit() {
        quit.countDown();
    }

    @Override
    public void close() {
        shutdown();
    }

    // ---- build ----

    private void open() {
        loaded = Wiring.loadConfig(options, env);
        config = loaded.config();
        Wiring.configureLogging(config, env, false);
        Wiring.warnUnattended(options, env);
        loaded.warnings().forEach(w -> startupNotices.add("Warning: " + w));
        keys = resolveKeys();
        cwd = Wiring.workDir(options, env);
        home = env.home();
        trusted = loaded.trusted();
        roles = ModelRoles.from(config.models());
        window = Wiring.contextWindow(loaded, roles.defaultModel());
        var resolver = new ApiKeyResolver(env.env());
        scrubber = resolver.scrubber(config);

        // Fail early, before the screen starts, when no model client can be made.
        clientFor(null);

        sessions = new SessionStore(cwd);
        var conversation = resume == null
                ? sessions.create(config)
                : (resume.isBlank() ? sessions.resumeLatest() : sessions.resume(resume))
                        .orElseThrow(() -> new CliException(resume.isBlank()
                                ? "No session found in this project." : "Session not found: " + resume));
        main.session(conversation);
        embedder = Wiring.embedder(config, env);
        countingStore = new ScrubbingContextStore(
                LuceneContextStore.open(conversation.indexDir(), embedder, LuceneContextStore.Options.defaults().withUserBoost(config.index().userBoost())), scrubber);
        main.store(countingStore);
        main.transcript(new TranscriptWriter(conversation.transcriptPath(), scrubber));
        policy = Wiring.policy(config, options, env, cwd, conversation.id(), scrubber);
        git = GitRunner.process();

        var colors = -1;
        try {
            colors = realTerminal.getNumericCapability(org.jline.utils.InfoCmp.Capability.max_colors) instanceof Integer c
                    ? c : -1;
        } catch (RuntimeException e) {
            log.atDebug().log(() -> "Cannot read the color count: " + e);
        }
        var highlight = ChatHighlighting.build(config, env.env(), cwd, home, colors);
        screen = new Screen(proxyOf(realTerminal), new Screen.Options(config.ui().keyboardProtocol(),
                hooks.historyFile() != null ? hooks.historyFile() : home.resolve(".jeorje").resolve("history"),
                this::onSubmit, this::onDecision, this::onInterrupt, keys, new Actions()), highlight);
        clipboard = new org.github.lscoughlin.jeorje.ui.mouse.Clipboard(realTerminal,
                org.github.lscoughlin.jeorje.ui.mouse.Clipboard.osc52Likely(env.env()));
        screen.status().model(roles.defaultModel()).yolo(options.yolo());
        gate = new PromptGate(screen::post);
        askTrustFirst();

        rescanRegistries();
        openWorktrees();
        buildAgents();
        buildOrchestrator();
        if (resume != null) restoreAgent();

        roster = new AgentRoster(MAIN, () -> {
            var list = new ArrayList<SubagentRuntime>();
            if (main.runtime() != null) list.add(main.runtime());
            if (orchestrator != null) list.add(orchestrator.runtime());
            return list;
        }, () -> swarm == null ? List.of() : AgentRoster.alive(swarm.peers(PeerState.ALIVE)),
                () -> branchOf(MAIN), () -> main.busy().get() ? "RUNNING" : "IDLE", this::historyOf, this::steerMain);
        var size = realTerminal.getSize();
        switcher = new AgentSwitcher(roster, MAIN,
                new org.github.lscoughlin.jeorje.ui.mouse.Rect(0, 0, Math.max(1, size.getColumns()),
                        Math.max(1, size.getRows() - 2)), config.ui().mouse().wheelLines());
        switcher.onForeground(this::onForeground);
        commands = new SessionCommands(this);
        slash = new SlashCommands(new Backend());
        commands.register(slash);
        screen.setCompletion(new org.github.lscoughlin.jeorje.ui.commands.InputCompletion(slash, cwd));
        rescanRegistries();
    }

    private void openWorktrees() {
        if ("never".equals(config.worktree().isolation())) return;
        var inside = git.run(cwd, "rev-parse", "--is-inside-work-tree");
        if (!inside.ok() || !"true".equals(inside.trimmed())) return;
        try {
            GitRunner.process();
            worktrees = new WorktreeManager(git, cwd, config.worktree(), text -> notice(MAIN, text));
            worktrees.processStopper(BashTool::stopProcessesIn);
            finisher = new WorktreeFinisher(worktrees, (parent, note) -> notice(parent, note));
            completion = new WorktreeCompletion(worktrees, Verifier.shell(),
                    (parent, message) -> notice(MAIN, message));
            if (hooks.mergeRetryInterval() != null) completion.retryInterval(hooks.mergeRetryInterval());
            worktreeCommands = new WorktreeCommands(worktrees, completion, (parent, note) -> notice(MAIN, note));
            var report = worktrees.startup(List.of());
            if (!report.leftover().isEmpty()) {
                startupNotices.add("Leftover worktrees on disk: " + report.leftover().stream().map(Path::toString)
                        .reduce((a, b) -> a + ", " + b).orElse("") + ". Run /worktrees prune.");
            }
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "Worktrees are not available: " + e.getMessage());
            worktrees = null;
            worktreeCommands = null;
            completion = null;
            finisher = null;
        }
    }

    /** The policy that adds the {@code allowed-tools} grants of loaded skills to the real policy. */
    private SkillGrantPolicy skillGrants() {
        var grants = skillGrants;
        if (grants == null) {
            grants = new SkillGrantPolicy(policy, config.skills().honorAllowedTools());
            skillGrants = grants;
        }
        return grants;
    }

    /**
     * The policy of a subagent that works in a worktree. Its workspace is its worktree, so it cannot read or write
     * the main working tree. It has the mode, the rules, and the session rules of the real policy, and the skill
     * grants of the session. An {@code ASK} becomes a question to the user. It is never wider than the real policy.
     */
    private PermissionPolicy policyForWorkspace(PermissionPolicy parent, AgentId child, Path workspace) {
        var rooted = policy.rootedAt(child, workspace);
        var own = new GatedPolicy(skillGrants().over(rooted), null, this::askForWorker);
        // A call with a path goes through the sandbox of the worktree. Other calls (the story and sync tools, for
        // example) keep the decision of the parent policy.
        return action -> org.github.lscoughlin.jeorje.core.permissions.ToolPermissionPolicy.hasPath(action.input())
                ? own.check(action) : parent.check(action);
    }

    private AgentSettings agentSettings() {
        return new AgentSettings(main.session().id(), cwd, window, config.context().budgetPercent(),
                config.llm().maxOutputTokens(), config.context().recentTurns(), config.agents().stepLimit(),
                null, config.prompts().system(), "", null)
                .withInstructionsSource(Wiring.promptInstructions(config, cwd, () -> toolSkills)::text)
                .withMaxProtectedTokens(config.skills().maxProtectedTokens())
                .withRecentMaxPercent(config.context().recentMaxPercent())
                .withRepeatGuard(config.agents().repeatGuard());
    }

    private SubagentLimits limits() {
        return SubagentLimits.defaults()
                .withAskTimeout(Duration.ofSeconds(config.subagents().askTimeoutSeconds()))
                .withMaxAsks(config.subagents().maxAsks())
                .withMaxModelCalls(config.subagents().stepLimit());
    }

    /** The MCP servers of the session. They start once, at the first use. */
    private synchronized org.github.lscoughlin.jeorje.tools.mcp.McpManager mcpManager() {
        if (!mcpStarted) {
            mcpStarted = true;
            mcp = Wiring.mcp(config, options, policy.network(), cwd, env.env());
        }
        return mcp;
    }

    /** The text of the {@code /mcp} command. */
    String mcpReport() {
        return Wiring.mcpReport(mcpManager(), config, options.noMcp());
    }

    /** The tools of the coding agent as agent tools. */
    private List<Tool> baseTools(PermissionPolicy toolPolicy) {
        var state = new AgentState();
        if (toolState == null) toolState = state;
        var grants = skillGrants();
        var registry = Wiring.registry(config, grants, policy.network(), state, main.session().id(), options.yolo(),
                mcpManager());
        var out = new ArrayList<Tool>();
        for (var name : registry.names()) {
            var tool = registry.find(name).orElseThrow();
            out.add(new Tool() {
                @Override
                public org.github.lscoughlin.jeorje.core.model.ToolDefinition definition() { return tool.definition(); }

                @Override
                public boolean readOnly() { return tool.readOnly(); }

                @Override
                public ToolResultData execute(JsonNode input, ToolContext context) {
                    awaitWorktree(context);
                    var result = Wiring.scrubbed(registry.execute(new ToolUse("call", name, input), context),
                            scrubber);
                    if (!result.updates().isEmpty()) state.apply(result.updates());
                    return result;
                }
            });
        }
        toolSkills = Wiring.visibleSkills(skills, grants);
        SkillTool.create(toolSkills, grants::skillLoaded).ifPresent(t -> {
            skillTool = t;
            out.add(t);
        });
        out.add(advisor.slot());
        return out;
    }

    /**
     * Holds a tool call of a subagent until its worktree exists. A subagent starts with the directory of its future
     * worktree. The worktree is made right after the start. Without this wait, a fast tool call would make the
     * directory first, and git would then refuse to make the worktree there.
     */
    private void awaitWorktree(ToolContext context) {
        if (worktrees == null) return;
        var dir = context.workingDirectory().toAbsolutePath().normalize();
        if (!dir.startsWith(worktrees.projectRoot().resolve(".jeorje").resolve("worktrees"))) return;
        var end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try {
            while (worktrees.byAgent(context.agent()).isEmpty() && !context.cancel().isCancelled()
                    && System.nanoTime() < end) {
                Thread.sleep(5);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void buildAgents() {
        // Every agent has the advisor tool: the main agent, the subagents, and the workers. They all use this support.
        advisor = new AdvisorSupport(() -> roles.advisorSet(), () -> clientFor(roles.advisor()));
        var settings = agentSettings();
        var workerPolicy = new GatedPolicy(skillGrants(), null, this::askForWorker);
        main.policy(new GatedPolicy(skillGrants(), main.planMode(), null));
        var compactorClient = roles.pruningModel().map(this::clientFor).orElse(null);

        if (SwarmBridge.wanted(config)) {
            try {
                bridge = SwarmBridge.forConfig(config, home, cwd, env.env(), text -> notice(MAIN, text), this::refreshStatus);
            } catch (RuntimeException e) {
                log.atWarn().log(() -> "The swarm did not start: " + e);
                startupNotices.add("The swarm did not start: " + e.getMessage());
            }
        }
        var runtimeBuilder = SubagentRuntime.builder(main.session().id(),
                        settings, model -> advisor.tap(clientFor(model)))
                .store(main.store()).transcript(main.transcript()).agentEvents(this::onEvent)
                .pruneListener((child, chunks) -> releaseSkills(child.value(), chunks))
                .events(event -> {
                    if (bridge != null) bridge.onSubagentEvent(event);
                    if (finisher != null) finisher.accept(event);
                    refreshStatus();
                }).types(agentTypes).limits(limits()).compactor(compactorClient)
                .clock(Clock.systemUTC()).steps(completion != null ? completion : CompletionSteps.none())
                .workspacePolicies(this::policyForWorkspace);
        if (bridge != null) runtimeBuilder.remote(bridge);
        main.runtime(runtimeBuilder.build());

        var tools = new LinkedHashMap<String, Tool>();
        baseTools(main.policy()).forEach(t -> tools.put(t.definition().name(), t));
        var rootTools = List.copyOf(tools.values());
        tools.put(PlanSubmitTool.NAME, new PlanSubmitTool(config.plan().dir(), main.planMode(), this::onPlanSubmitted));
        broker = new UserQuestionBroker(this::onQuestion);
        tools.put(AskUserTool.NAME, new AskUserTool(broker));
        var agentTools = AgentTools.install(main.runtime(), main.session().id());
        var peerTools = bridge == null ? agentTools : PeerSpawnTool.wrap(agentTools, main.runtime());
        WorktreeSpawnTool.wrap(peerTools, main.runtime(), worktrees, () -> agentTypes)
                .forEach(t -> tools.putIfAbsent(t.definition().name(), t));

        var llm = new LlmClient() {
            @Override
            public Flow.Publisher<LlmEvent> stream(LlmRequest request, CancellationToken cancel) {
                return advisor.tap(clientFor(modelOverride)).stream(request, cancel);
            }
        };
        var self = new AtomicReference<Agent>();
        var agent = new Agent(MAIN, settings, llm, List.copyOf(tools.values()), main.policy(), main.store(),
                main.transcript(), this::onEvent, Clock.systemUTC());
        main.agent(agent);
        self.set(agent);
        main.planMode().maxExploreCalls(config.plan().maxExploreCalls());
        agent.withToolFilter(main.planMode()::refuse)
                .withStepBoundary(() -> main.planMode().notes());
        agent.withPruneListener(chunks -> releaseSkills(MAIN.value(), chunks));
        var compaction = config.context().compaction();
        var store = main.store();
        pruner = new Pruner(compactorClient, store, TermStats.of(store, countingStore::appendedCount),
                new Pruner.Config(compaction.levels(), compaction.batchChunks(), compaction.batchTokens(),
                        compaction.maxKeywords(), config.prompts().compaction(), cwd));
        normalCompactor = new ContextCompactor(pruner, CompactionSettings.from(config.context(), window), MAIN);
        agent.withCompactor(normalCompactor).withHardCompactor(hardCompactor(MAIN));
        main.runtime().registerRoot(MAIN, "main", new CancellationToken(), rootTools, workerPolicy,
                input -> self.get().submit(input), () -> self.get().turns().size() + 1);
        if (bridge != null) {
            bridge.attach(main.runtime(), rootTools, workerPolicy, input -> self.get().submit(input),
                    () -> main.store());
        }
        agent.start();
    }

    /**
     * Restores the main agent from the manifest and the transcript of the resumed session. It also shows the end of
     * the earlier conversation in the chat.
     */
    private void releaseSkills(String agentId, List<org.github.lscoughlin.jeorje.core.model.Chunk> chunks) {
        var skills = skillTool;
        if (skills != null) skills.releasePruned(agentId, chunks);
    }

    private void restoreAgent() {
        var conversation = main.session();
        var agent = main.agent();
        var transcriptTurns = TranscriptReader.read(conversation.transcriptPath());
        var source = Wiring.rootAgentOf(conversation.manifest());
        agent.restore(conversation.manifest(), transcriptTurns, source);
        resumedTurns = agent.turns().size();
        var recent = agent.turns().subList(Math.max(0, resumedTurns - 3), resumedTurns);
        var lines = new ArrayList<String>();
        lines.add("Resumed session " + conversation.id() + " with " + resumedTurns + " turns. Last turns:");
        for (var turn : recent) {
            for (var message : turn.messages()) {
                var sb = new StringBuilder();
                message.content().forEach(c -> {
                    if (c instanceof Text t) sb.append(t.text());
                });
                if (sb.isEmpty()) continue;
                var text = sb.toString().strip().replace('\n', ' ');
                lines.add((message.role() == Role.USER ? "  you: " : "  agent: ")
                        + (text.length() > 160 ? text.substring(0, 160) + "..." : text));
            }
        }
        startupNotices.add(String.join("\n", lines));
    }

    private void buildOrchestrator() {
        var settings = agentSettings();
        var workerPolicy = new GatedPolicy(skillGrants(), null, this::askForWorker);
        var builder = ImplementOrchestrator.builder(main.session().id(),
                        settings, model -> advisor.tap(clientFor(model)), config.implement())
                .store(main.store()).transcript(main.transcript()).worktrees(worktrees)
                .stateStore(RunStateStore.file(main.session().dir().resolve("implement.json")))
                .events(this::onImplementEvent).agentEvents(this::onEvent).subagentEvents(e -> refreshStatus())
                .orchestratorInput(input -> notice(MAIN, input.text()))
                .limits(limits()).policy(workerPolicy).workspacePolicies(this::policyForWorkspace)
                .approver(plan -> askApproval(plan.render()))
                .baseTools(baseTools(workerPolicy))
                .workerTools(StoryTools.factory(List.of()));
        if (hooks.mergeRetryInterval() != null) builder.mergeRetryInterval(hooks.mergeRetryInterval());
        if (roles.advisorSet() && "advisor".equals(config.implement().review())) {
            builder.reviewer(new AdvisorReviewer(clientFor(roles.advisor())));
        }
        var compactorClient = roles.pruningModel().map(this::clientFor).orElse(null);
        builder.compactor(compactorClient);
        orchestrator = builder.build();
    }

    private LlmClient clientFor(String model) {
        var name = model == null || model.isBlank() ? roles.defaultModel() : model;
        return clients.computeIfAbsent(name, m -> Wiring.llmClient(config, env, m));
    }

    // ---- ui ----

    private void startUi() {
        if (!screenStarted) screen.start();
        screenStarted = true;
        uiStarted = true;
        var mouseConfig = config.ui().mouse();
        registry = new RegionRegistry();
        mouse = new MouseInput(realTerminal, new MouseConfig(mouseConfig.enabled(), Math.max(1, mouseConfig.wheelLines()),
                mouseConfig.copyOnSelect()), registry, text -> screen.status().notice(text));
        var wheel = Math.max(1, mouseConfig.wheelLines());
        chatMouse = new org.github.lscoughlin.jeorje.ui.mouse.ChatMouse(() -> screen.chat(screen.foreground()),
                screen::chatTextArea, screen::chatBarArea, screen::followNoticeArea, clipboard,
                new MouseConfig(mouseConfig.enabled(), wheel, mouseConfig.copyOnSelect()),
                text -> screen.status().notice(text), this::openClickedPath, screen::draw);
        chatMouse.regions().forEach(registry::register);
        if (mouse.start()) screen.setScrollBars(true, wheel);
        olderLinesFor(MAIN);
        startupNotices.forEach(text -> notice(MAIN, text));
        notice(MAIN, "Jeorje is ready. Type /help for the commands.");
        startSwarm();
        ticker = Thread.ofVirtual().name("jeorje-ui-status").start(this::tickLoop);
        refreshStatus();
        screen.draw();
    }

    /** The terminal that the screen and the line reader use. It sends mouse events to the mouse regions. */
    private Terminal proxyOf(Terminal real) {
        return (Terminal) Proxy.newProxyInstance(Terminal.class.getClassLoader(), new Class<?>[] {Terminal.class},
                (proxy, method, args) -> {
                    try {
                        var result = method.invoke(real, args);
                        if (method.getName().equals("readMouseEvent") && result instanceof MouseEvent event
                                && mouse != null) {
                            try {
                                mouse.handle(event);
                            } catch (RuntimeException e) {
                                log.atDebug().log(() -> "mouse event failed: " + e);
                            }
                        }
                        return result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private void startSwarm() {
        if (bridge == null) return;
        try {
            swarm = bridge.start().node();
            notice(MAIN, "Swarm started on " + swarm.address() + ".");
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "The swarm did not start: " + e);
            notice(MAIN, "The swarm did not start: " + e.getMessage());
            swarm = null;
        }
    }

    /**
     * Asks the trust question before the agents exist, so that the agents start with the project skills, commands,
     * agent types, and config when the user answers yes. It starts the screen early for this. It does nothing when
     * the project is trusted or has no project items.
     */
    private void askTrustFirst() {
        if (trusted || !Scopes.hasProjectItems(cwd)) return;
        screen.start();
        screenStarted = true;
        notice(MAIN, "This project has skills, commands, or agent definitions. Jeorje loads them only if you trust "
                + "the project.");
        var input = MAPPER.createObjectNode().put("project", cwd.toString())
                .put("loads", "skills, commands, and agent definitions of the project");
        var answer = new java.util.concurrent.CompletableFuture<Decision>();
        Thread.ofVirtual().name("jeorje-trust-prompt").start(() ->
                answer.complete(gate.ask(MAIN, new ToolUse("trust-" + promptIds.incrementAndGet(), "trust_project",
                        input))));
        try {
            while (quit.getCount() > 0) {
                try {
                    var decision = answer.get(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (decision.approved()) {
                        new TrustStore(home).trust(cwd);
                        reloadAfterTrust();
                        notice(MAIN, "This project is now trusted.");
                    } else {
                        notice(MAIN, "The project stays untrusted. Use /trust to trust it later.");
                    }
                    return;
                } catch (java.util.concurrent.TimeoutException e) {
                    // wait on
                }
            }
            gate.denyAll();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            log.atWarn().log(() -> "The trust prompt failed: " + e.getCause());
        }
    }

    /** Reads the config again, now with the project config, and builds the parts that depend on it. */
    private void reloadAfterTrust() {
        loaded = Wiring.loadConfig(options, env);
        config = loaded.config();
        trusted = true;
        roles = ModelRoles.from(config.models());
        window = Wiring.contextWindow(loaded, roles.defaultModel());
        scrubber = new ApiKeyResolver(env.env()).scrubber(config);
        clients.clear();
        policy = Wiring.policy(config, options, env, cwd, main.session().id(), scrubber);
        skillGrants = null;
        screen.status().model(roles.defaultModel());
    }

    void trustProject() {
        new TrustStore(home).trust(cwd);
        trusted = true;
        rescanRegistries();
        notice(screen.foreground(), "The skills and commands of the project are loaded now. The skill tool, the "
                + "project config, and the types of subagents apply from the next start.");
    }

    void revokeTrust() {
        new TrustStore(home).revoke(cwd);
        trusted = false;
        rescanRegistries();
        notice(screen.foreground(), "The project is not trusted now. The skill tool and the types of subagents "
                + "change at the next start.");
    }

    boolean projectTrusted() { return trusted; }

    private void rescanRegistries() {
        var bases = new ArrayList<Path>();
        config.skills().dirs().forEach(d -> bases.add(Path.of(d)));
        config.agents().dirs().forEach(d -> bases.add(Path.of(d)));
        skills = Wiring.skills(config, home, cwd, trusted);
        var builtIns = slash == null ? java.util.Set.<String>of() : slash.builtInNames();
        commandRegistry = CommandRegistry.scan(Scopes.roots("commands", home, cwd, trusted, bases, List.of()), builtIns);
        agentTypes = AgentTypeRegistry.scan(Scopes.roots("agents", home, cwd, trusted, bases, List.of()));
    }

    // ---- status ----

    private void tickLoop() {
        while (!closed.get()) {
            try {
                Thread.sleep(300);
                refreshStatus();
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.atDebug().log(() -> "status tick failed: " + e);
            }
        }
    }

    /** Updates the status line from the live state. Draws when the text changed. */
    void refreshStatus() {
        if (screen == null || !uiStarted) return;
        var status = screen.status();
        status.model(roles.defaultModel()).yolo(options.yolo()).plan(main.planMode().active());
        status.branch(branchOf(screen.foreground()));
        status.peers(swarm == null ? 0 : swarm.peers(PeerState.ALIVE).size());
        var rows = orchestrator == null ? List.<ImplementOrchestrator.Row>of() : orchestrator.rows();
        if (orchestrator != null && (orchestrator.active() || !rows.isEmpty())) {
            var done = (int) rows.stream().filter(r -> r.state()
                    == org.github.lscoughlin.jeorje.core.implement.StoryState.DONE).count();
            status.implement(done, rows.size());
        } else {
            status.clearImplement();
        }
        var text = status.text();
        if (!text.equals(lastStatus)) {
            lastStatus = text;
            screen.draw();
        }
    }

    private String branchOf(AgentId id) {
        if (!id.equals(MAIN)) {
            for (var r : List.of(main.runtime(), orchestrator.runtime())) {
                var info = r.info(id);
                if (info.isPresent() && info.get().branch() != null) return info.get().branch();
            }
            return "";
        }
        var now = System.currentTimeMillis();
        if (git != null && now - branchCheckedAt > 3000) {
            branchCheckedAt = now;
            var result = git.run(cwd, "rev-parse", "--abbrev-ref", "HEAD");
            cachedBranch = result.ok() ? result.trimmed() : "";
        }
        return cachedBranch;
    }

    // ---- events ----

    private void onEvent(AgentEvent raw) {
        var event = cleanEvent(raw);
        if (bridge != null) bridge.onAgentEvent(event);
        screen.post(event);
        notifyEditor(event);
        if (!event.agent().equals(MAIN)) return;
        switch (event) {
            case AgentEvent.TokenDelta d -> main.busy().set(true);
            case AgentEvent.ToolStarted s -> main.busy().set(true);
            case AgentEvent.TurnFinished t -> {
                main.busy().set(false);
                modelOverride = null;
                saveManifest("idle");
            }
            default -> { }
        }
    }

    private void onImplementEvent(ImplementEvent event) {
        var line = switch (event) {
            case ImplementEvent.RunStarted s ->
                    "IMPLEMENT: plan " + s.plan() + ", " + s.open() + " of " + s.stories() + " stories are open.";
            case ImplementEvent.StoryStarted s -> "IMPLEMENT: " + s.storyId() + " started (attempt " + s.attempt()
                    + ", worker " + s.worker() + (s.branch() == null || s.branch().isBlank() ? "" : ", branch "
                    + s.branch()) + ").";
            case ImplementEvent.StoryChanged c -> "IMPLEMENT: " + c.storyId() + " is " + c.state().label()
                    + (c.detail() == null || c.detail().isBlank() ? "" : ": " + c.detail());
            case ImplementEvent.TaskFinished t -> "IMPLEMENT: " + t.storyId() + " " + t.taskId() + " done.";
            case ImplementEvent.Reviewed r -> "IMPLEMENT: review of " + r.storyId() + " "
                    + (r.approved() ? "approved." : "found problems: " + r.problems());
            case ImplementEvent.Notice n -> n.text();
            case ImplementEvent.RunControl c -> "IMPLEMENT: " + c.action() + ".";
            case ImplementEvent.RunFinished f -> f.report().render();
        };
        notice(MAIN, line);
        refreshStatus();
        if (event instanceof ImplementEvent.RunFinished) saveManifest("idle");
    }

    /** Appends lines to the chat of an agent. */
    void notice(AgentId agentId, String text) {
        if (screen == null) return;
        var chat = screen.chat(agentId);
        for (var line : text.stripTrailing().split("\n", -1)) chat.appendNotice(line);
        if (uiStarted) screen.draw();
    }

    private void onQuestion(UserQuestion question) {
        var form = question.form();
        var title = form.title() != null && !form.title().isBlank() ? form.title() : form.firstPrompt();
        var text = "Question from " + question.agent() + ": " + title;
        notice(question.agent(), text);
        if (!question.agent().equals(MAIN)) notice(MAIN, text);
        screen.status().state("waiting for you");
        screen.showForm(new FormPrompt(question.agent(), form), answer -> {
            broker.answer(question.id(), answer);
            screen.status().state("working");
        });
    }

    private void onPlanSubmitted(Path file) {
        var text = new StringBuilder("Plan written: " + file + "\n");
        try {
            text.append(Files.readString(file));
        } catch (java.io.IOException e) {
            text.append("(cannot read the plan: ").append(e.getMessage()).append(')');
        }
        text.append("\nUse /plan edit to change the plan, ask for changes, or use /plan done to leave plan mode.");
        notice(MAIN, text.toString());
    }

    // ---- permission ----

    private PermissionPolicy.Decision askForWorker(AgentId who, ToolUse use) {
        var story = orchestrator == null ? Optional.<String>empty() : orchestrator.storyOf(who);
        var label = story.orElseGet(() -> main.runtime() == null ? who.value()
                : main.runtime().nameOf(who.value()));
        screen.status().notice(label + " asks to run " + use.tool());
        var decision = gate.ask(who, use);
        rememberDecision(use, decision);
        return decision.approved() ? PermissionPolicy.Decision.ALLOW : PermissionPolicy.Decision.DENY;
    }

    private void rememberDecision(ToolUse use, Decision decision) {
        policy.recordUserDecision(use, decision.approved() ? PermissionPolicy.Decision.ALLOW
                : PermissionPolicy.Decision.DENY);
        if (decision == Decision.ALLOW_SESSION || decision == Decision.ALLOW_PROJECT) {
            policy.addSessionRule(new Rule(use.tool(), null, PermissionPolicy.Decision.ALLOW));
        }
        if (decision == Decision.ALLOW_PROJECT) saveProjectRule(use.tool());
    }

    /** Writes the rule "allow this tool" into the project config. It does so only in a trusted project. */
    private void saveProjectRule(String tool) {
        if (!trusted) {
            notice(MAIN, "The project is not trusted. The rule to allow " + tool + " holds for this session only. "
                    + "Use /trust to trust the project.");
            return;
        }
        try {
            var changed = ProjectRuleWriter.allow(cwd, tool);
            notice(MAIN, changed ? "Saved a rule to allow " + tool + " in " + ProjectRuleWriter.configFile(cwd) + "."
                    : "The project config already allows " + tool + ".");
        } catch (java.io.IOException | RuntimeException e) {
            log.atWarn().log(() -> "Cannot save the rule for " + tool + ": " + e.getMessage());
            notice(MAIN, "Cannot save the rule in the project config: " + e.getMessage()
                    + " The rule holds for this session only.");
        }
    }

    private void onDecision(AgentId who, ToolUse use, Decision decision) {
        if (gate.complete(who, use, decision)) return;
        if (who.equals(MAIN)) {
            rememberDecision(use, decision);
            main.agent().confirm(use.id(), decision.approved());
        }
    }

    private boolean askApproval(String planText) {
        var input = MAPPER.createObjectNode().put("plan", planText);
        var decision = gate.ask(MAIN, new ToolUse("approve-" + promptIds.incrementAndGet(), "approve_plan", input));
        return decision.approved();
    }

    // ---- input ----

    private void onSubmit(String line) {
        try {
            promptSent = false;
            if (line.strip().startsWith("!")) {
                runBang(line.strip().substring(1).strip());
                return;
            }
            var result = slash.execute(line);
            if (result.handled()) {
                if (!result.output().isBlank()) notice(screen.foreground(), result.output());
                if (!promptSent) screen.status().state("idle");
                screen.draw();
                return;
            }
            SessionMentions.addPaths(line, cwd, attachments);
            var text = line;
            if (!attachments.isEmpty()) {
                text = "Files: " + String.join(", ", attachments) + "\n" + line;
                attachments.clear();
            }
            var target = screen.foreground();
            if (target.equals(MAIN)) {
                main.busy().set(true);
                main.planMode().resetExploration();
                // A text during a turn joins that turn. A text when the agent has no turn starts a turn.
                if (!main.agent().steer(text)) main.agent().submit(new AgentInput.UserInput(text));
            } else {
                roster.steer(target, text);
            }
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "Input failed: " + e);
            notice(screen.foreground(), "Error: " + e.getMessage());
            screen.status().state("idle");
        }
    }

    /**
     * Runs a bang line. The command runs in the working directory with no permission check. The chat view shows
     * one block. The text of the block goes to the foreground agent as a note. The line starts no turn.
     */
    private void runBang(String command) {
        var target = screen.foreground();
        if (command.isEmpty()) {
            notice(target, "Usage: !<command>");
            if (!main.busy().get()) screen.status().state("idle");
            screen.draw();
            return;
        }
        var result = ShellRunner.run(cwd, command, ShellSettings.defaults(), BANG_TIMEOUT);
        var body = result.output().isEmpty() ? "(no output)" : result.output();
        var footer = result.timedOut() ? "[timed out after " + BANG_TIMEOUT.toSeconds() + " s]"
                : "[exit code " + result.exitCode() + "]";
        screen.localBlock(target, "! " + command, body, footer);
        var note = "Shell command (run by the user, not by you): " + command + "\nWorking directory: " + cwd
                + "\nOutput:\n" + body + "\n" + footer;
        note = hideHome(note);
        if (target.equals(MAIN)) {
            main.agent().note(note);
        } else {
            roster.steer(target, note);
        }
        if (!main.busy().get()) screen.status().state("idle");
        screen.draw();
    }

    /** Replaces each home directory path in a text with {@code ~}. */
    private String hideHome(String text) {
        var homes = new java.util.LinkedHashSet<String>();
        if (home != null) homes.add(home.toString());
        homes.add(System.getProperty("user.home"));
        homes.add(System.getenv("HOME"));
        var out = text;
        for (var h : homes.stream().filter(h -> h != null && h.length() > 1).sorted(
                java.util.Comparator.comparingInt(String::length).reversed()).toList()) {
            out = out.replace(h, "~");
        }
        return out;
    }

    void sendPrompt(String prompt, String model) {
        promptSent = true;
        modelOverride = model;
        main.busy().set(true);
        main.agent().submit(new AgentInput.UserInput(prompt));
    }

    private void onInterrupt() {
        var now = System.currentTimeMillis();
        if (now - lastInterrupt < hooks.interruptWindow().toMillis()) {
            quit.countDown();
            return;
        }
        lastInterrupt = now;
        var cancelled = cancel(null);
        notice(screen.foreground(), cancelled + " Press Ctrl-C again to exit.");
        screen.status().state("idle");
        screen.draw();
    }

    /** Cancels the foreground agent, or the named agent. */
    String cancel(String name) {
        var target = name == null ? screen.foreground() : roster.find(name).map(e -> e.id()).orElse(null);
        if (target == null) return "No agent named " + name + ".";
        if (target.equals(MAIN)) {
            main.agent().cancel();
            gate.denyAgent(MAIN);
            screen.cancelForms(MAIN);
            return "Interrupted the main agent.";
        }
        var any = false;
        for (var r : List.of(main.runtime(), orchestrator.runtime())) any |= r.cancel(target.value());
        gate.denyAgent(target);
        screen.cancelForms(target);
        return any ? "Interrupted " + target + "." : "Nothing to interrupt for " + target + ".";
    }

    private void steerMain(AgentId id, String text) {
        main.busy().set(true);
        main.agent().submit(new AgentInput.UserInput(text));
    }

    private List<String> historyOf(AgentId id) {
        return screen.chat(id).logicalLines().stream().map(Object::toString).toList();
    }

    // ---- keys, panes, and editor ----

    /** The key bindings from the config. A config error stops the start with a clear message. */
    private org.github.lscoughlin.jeorje.ui.keys.KeyBindings resolveKeys() {
        try {
            return org.github.lscoughlin.jeorje.ui.keys.KeyBindings.resolve(List.of(
                    new org.github.lscoughlin.jeorje.ui.keys.KeyLayer("config", config.ui().keys(),
                            config.editor().keys())));
        } catch (org.github.lscoughlin.jeorje.ui.keys.KeyConfigException e) {
            throw new CliException("The key config has an error: " + e.getMessage());
        }
    }

    /** The key actions of the chat screen. They run on the input thread. */
    private final class Actions implements ActionHandler {
        @Override
        public void handle(String actionId) {
            try {
                switch (actionId) {
                    case "agents" -> openSwitcherPane();
                    case "files" -> openBrowser();
                    case "copySelection" -> copyChatSelection();
                    default -> { }
                }
            } catch (RuntimeException e) {
                log.atWarn().log(() -> "Action " + actionId + " failed: " + e);
                notice(screen.foreground(), "Error: " + e.getMessage());
            }
        }

        @Override
        public void exit() { quit.countDown(); }
    }

    /** Copies the selected chat text with OSC 52. The internal clipboard keeps it when the terminal cannot. */
    void copyChatSelection() {
        var text = chatMouse != null ? chatMouse.copySelection() : "";
        if (text.isEmpty()) screen.status().notice("Nothing is selected.");
        screen.draw();
    }

    /** A click on a file path in the chat view. An existing file opens in the editor, like {@code /edit}. */
    void openClickedPath(String path) {
        var file = cwd.resolve(path).normalize();
        if (!java.nio.file.Files.isRegularFile(file)) return;
        Thread.ofVirtual().name("jeorje-open-path").start(() -> screen.runOnInput(() -> editFile(file)));
    }

    /** Lets the chat view of an agent load older lines from {@code transcript.jsonl}. */
    private void olderLinesFor(AgentId id) {
        try {
            var source = new org.github.lscoughlin.jeorje.ui.mouse.TranscriptOlderLines(
                    main.session().transcriptPath(), id);
            screen.chat(id).olderLines(source, source.lineCount());
        } catch (RuntimeException e) {
            log.atDebug().log(() -> "No older lines for " + id + ": " + e);
        }
    }

    private void onForeground(AgentId id) {
        if (id.value().startsWith(AgentRoster.PEER_PREFIX)) {
            notice(screen.foreground(), "That agent is a remote peer. You cannot switch into it.");
            switcher.back();
            return;
        }
        screen.setForeground(id);
        refreshStatus();
    }

    /** Shows the agent switcher in the chat region. Enter on an agent makes it the foreground agent. */
    void openSwitcherPane() {
        screen.runPane(new AgentSwitcherPane(switcher));
        refreshStatus();
    }

    /** Shows the file browser in the chat region. Enter on a file opens the file in the editor. */
    void openBrowser() {
        var size = realTerminal.getSize();
        var browser = new FileBrowser(cwd, new org.github.lscoughlin.jeorje.ui.mouse.Rect(0, 1,
                Math.max(1, size.getColumns()), Math.max(1, size.getRows() - 3)), config.ui().mouse().wheelLines());
        browser.onAdd(path -> {
            attachments.add(cwd.relativize(path.toAbsolutePath().normalize()).toString());
            screen.status().notice("Added " + path.getFileName() + " to the next message.");
        });
        var picked = screen.runPane(new FileBrowserPane(browser, keys));
        picked.ifPresent(this::editFile);
    }

    /** The state tables that the file tools of the foreground agent read. */
    private List<AgentState> stateTables() {
        var tables = new ArrayList<AgentState>();
        if (toolState != null) tables.add(toolState);
        var foreground = screen.foreground();
        var target = foreground.equals(MAIN) ? main.agent() : Agent.live(foreground).orElse(null);
        if (target != null) tables.add(target.state());
        return tables;
    }

    private void fileSaved(Path path, org.github.lscoughlin.jeorje.core.state.FileState state) {
        var update = List.<org.github.lscoughlin.jeorje.core.state.StateUpdate>of(
                new org.github.lscoughlin.jeorje.core.state.StateUpdate.FileRecorded(path.toAbsolutePath().normalize(),
                        state));
        stateTables().forEach(table -> table.apply(update));
    }

    private Map<String, Object> flatConfig() {
        var flat = new LinkedHashMap<String, Object>();
        flatten("", loaded.tree(), flat);
        return flat;
    }

    private static void flatten(String prefix, Map<?, ?> tree, Map<String, Object> out) {
        for (var entry : tree.entrySet()) {
            var key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> child) flatten(key, child, out);
            else out.put(key, entry.getValue());
        }
    }

    private volatile org.github.lscoughlin.jeorje.ui.syntax.HighlightService highlight;
    private volatile org.github.lscoughlin.jeorje.ui.syntax.SyntaxCatalog catalog;

    /** Makes the syntax catalog and the highlight service for the editor once. A failure gives plain text. */
    private synchronized void prepareSyntax() {
        if (catalog != null || !config.editor().syntax().enabled()) return;
        try {
            var syntax = config.editor().syntax();
            var settings = org.github.lscoughlin.jeorje.ui.syntax.SyntaxSettings
                    .defaults(cwd, home, org.github.lscoughlin.jeorje.ui.syntax.SyntaxCatalog.currentVersion())
                    .withConfigDirs(syntax.dirs().stream().map(Path::of).toList())
                    .withEmbedded(syntax.embedded());
            if (!syntax.systemDirs()) settings = settings.withSystemDirs(List.of());
            catalog = org.github.lscoughlin.jeorje.ui.syntax.SyntaxCatalog.load(settings);
            highlight = new org.github.lscoughlin.jeorje.ui.syntax.HighlightService(
                    new org.github.lscoughlin.jeorje.ui.syntax.HighlightOptions(true, syntax.replayLines(),
                            syntax.maxLineChars(), syntax.budgetMs(), syntax.maxBytes(),
                            System.getenv("NO_COLOR") != null));
        } catch (RuntimeException e) {
            log.atDebug().log(() -> "No syntax colors in the editor: " + e);
        }
    }

    /**
     * Runs the editor on the input thread. The editor owns the keyboard until the user closes it. The chat comes
     * back with mouse tracking on.
     */
    void editFile(Path file) {
        prepareSyntax();
        var hooks = new org.github.lscoughlin.jeorje.ui.editor.EditorHooks(this::fileSaved, flatConfig(), keys,
                text -> screen.status().notice(text), highlight, catalog, null, false);
        screen.suspendDrawing();
        if (mouse != null) mouse.suspend();
        try {
            var editor = newEditor(hooks);
            editor.open(file);
            activeEditor = editor;
            editor.run(realTerminal);
        } catch (java.io.IOException | RuntimeException e) {
            notice(screen.foreground(), "The editor failed: " + e.getMessage());
        } finally {
            activeEditor = null;
            realTerminal.puts(org.jline.utils.InfoCmp.Capability.enter_ca_mode);
            realTerminal.puts(org.jline.utils.InfoCmp.Capability.clear_screen);
            realTerminal.flush();
            if (mouse != null) mouse.resume();
            screen.repaint();
        }
    }

    /**
     * Builds the editor like {@link QEditor#edit} does. The session needs the editor object while it runs, to call
     * {@link QEditor#externalChange}, and {@code edit} returns it only after the user closed it.
     */
    private QEditor newEditor(org.github.lscoughlin.jeorje.ui.editor.EditorHooks hooks) {
        var map = hooks.config();
        var wheel = map.get("ui.mouse.wheelLines") instanceof Number n ? Math.max(1, n.intValue()) : 3;
        var options = org.github.lscoughlin.jeorje.ui.editor.EditorOptions.defaults().withWheelLines(wheel);
        if (map.get("editor.syntax.enabled") instanceof Boolean b && !b) options = options.withSyntaxColors(false);
        var injections = org.github.lscoughlin.jeorje.ui.editor.markdown.InjectionOptions.fromConfig(map);
        if (map.get("editor.syntax.replayLines") instanceof Number n) {
            injections = injections.withReplayLines(Math.max(1, n.intValue()));
        }
        return QEditor.builder().keys(hooks.keys()).clipboard(hooks.clipboard()).catalog(hooks.catalog())
                .highlight(hooks.highlight()).fileStates(hooks.fileStates()).options(options)
                .injections(injections).noticeSink(hooks.agentChangeNotice()).baseDir(cwd).build();
    }

    /** The event without secrets. The chat, the peers, and the prompts never get the value of a key. */
    private AgentEvent cleanEvent(AgentEvent event) {
        if (scrubber == null) return event;
        return switch (event) {
            case AgentEvent.ToolStarted s -> new AgentEvent.ToolStarted(s.agent(), s.toolUseId(), s.tool(),
                    scrubber.scrub(s.summary()));
            case AgentEvent.ToolFinished f -> new AgentEvent.ToolFinished(f.agent(), f.toolUseId(),
                    Wiring.scrubbed(f.result(), scrubber));
            case AgentEvent.Error e -> new AgentEvent.Error(e.agent(), scrubber.scrub(e.message()));
            case AgentEvent.ConfirmationRequested c -> new AgentEvent.ConfirmationRequested(c.agent(),
                    new ToolUse(c.toolUse().id(), c.toolUse().tool(),
                            c.toolUse().input() == null ? null : scrubber.scrubTree(c.toolUse().input().deepCopy())));
            default -> event;
        };
    }

    /** Tells the open editor about a file that a tool of an agent changed. */
    private void notifyEditor(AgentEvent event) {
        switch (event) {
            case AgentEvent.ToolStarted s -> startedTools.put(s.toolUseId(), s.tool());
            case AgentEvent.ToolFinished f -> {
                var tool = startedTools.remove(f.toolUseId());
                var editor = activeEditor;
                if (editor == null || f.result().error() || tool == null || tool.equals("read")) return;
                for (var update : f.result().updates()) {
                    if (update instanceof org.github.lscoughlin.jeorje.core.state.StateUpdate.FileRecorded r) {
                        editor.externalChange(r.path());
                    }
                }
            }
            default -> { }
        }
    }

    QEditor activeEditor() { return activeEditor; }

    /** The file-state table that the file tools of the main agent read. */
    AgentState toolState() { return toolState; }

    // ---- manifest ----

    private synchronized void saveManifest(String runState) {
        try {
            var agents = new ArrayList<SessionManifest.AgentNode>();
            agents.add(new SessionManifest.AgentNode(MAIN.value(), null, "main"));
            for (var info : main.runtime().status()) {
                if (info.parent() != null) {
                    agents.add(new SessionManifest.AgentNode(info.id().value(), info.parent().value(), info.name()));
                }
            }
            var agent = main.agent();
            var conversation = main.session();
            var manifest = agent.recordInto(conversation.manifest().withAgents(agents)
                    .withUpdate(Clock.systemUTC().instant(), agent.turns().size(), runState));
            conversation = conversation.with(manifest);
            main.session(conversation);
            conversation.save();
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "Cannot save the manifest: " + e.getMessage());
        }
    }

    // ---- shutdown ----

    private void shutdown() {
        if (!closed.compareAndSet(false, true)) return;
        if (gate != null) gate.denyAll();
        if (orchestrator != null) {
            try {
                if (orchestrator.active()) orchestrator.stop();
            } catch (RuntimeException e) {
                log.atWarn().log(() -> "Cannot stop the run: " + e);
            }
        }
        if (ticker != null) ticker.interrupt();
        if (main.agent() != null) main.agent().close();
        if (main.runtime() != null) main.runtime().close();
        if (mcp != null) closeQuietly(mcp);
        if (orchestrator != null) orchestrator.close();
        if (mouse != null) mouse.close();
        if (screen != null) screen.close();
        if (bridge != null) bridge.close();
        if (main.agent() != null && main.session() != null) saveManifest("idle");
        closeQuietly(main.transcript());
        closeQuietly(main.store());
        if (embedder instanceof AutoCloseable c) closeQuietly(c);
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception e) {
            log.atWarn().log(() -> "close failed: " + e.getMessage());
        }
    }

    /** Lists the worktrees that stay on disk. The list goes to standard output after the screen closed. */
    private void printKeptWorktrees() {
        if (worktrees == null) return;
        try {
            var lines = new ArrayList<String>();
            var known = new ArrayList<Path>();
            for (var row : worktrees.rows()) {
                lines.add("  " + row.name() + "  " + row.branch() + "  " + row.path() + "  " + row.state());
                known.add(row.path().toAbsolutePath().normalize());
            }
            for (var path : worktrees.listOnDisk()) {
                if (known.stream().noneMatch(k -> k.toString().equals(path.toString())
                        || k.getFileName().equals(path.getFileName()))) {
                    lines.add("  " + path);
                }
            }
            if (lines.isEmpty()) return;
            env.out().println("Kept worktrees:");
            lines.forEach(env.out()::println);
            env.out().println("Use /worktrees, /merge <agent>, or /discard <agent> in the next session.");
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "Cannot list the worktrees: " + e);
        }
    }

    // ---- compaction ----

    /**
     * Compacts the working context of the foreground agent at once, at the hard level. For the main view this is
     * the main agent. For a subagent view this is the foreground subagent. The next request is already smaller.
     */
    String compactForeground() {
        var foreground = screen.foreground();
        var target = foreground.equals(MAIN) ? main.agent() : Agent.live(foreground).orElse(null);
        if (target == null) return "The agent of this view is not running. There is nothing to compact.";
        var recent = config.context().recentTurns();
        if (target.turns().size() <= recent) {
            return "There is nothing to compact. The last " + recent + " turns stay as they are.";
        }
        target.withHardCompactor(hardCompactor(foreground));
        var result = target.compactNow();
        if (result.chunksPruned() == 0) {
            return "There is nothing to prune. All old chunks are at their highest level.";
        }
        return "Compacted " + result.chunksPruned() + " chunks and freed " + result.tokensFreed()
                + " tokens. The next request is smaller.";
    }

    /** The context report of the foreground agent, for the {@code /context} command. */
    String contextReport() {
        var foreground = screen.foreground();
        var target = foreground.equals(MAIN) ? main.agent() : Agent.live(foreground).orElse(null);
        if (target == null) return "The agent of this view is not running. There is no context to show.";
        return target.contextReport().render();
    }

    /** A compactor with a budget so small that every old chunk is over the hard limit. */
    private ContextCompactor hardCompactor(AgentId who) {
        var base = CompactionSettings.from(config.context(), window);
        var hard = new CompactionSettings(1, base.softPercent(), base.hardPercent(), base.recentTurns(),
                base.aggressiveDropAfterTurns(), base.batchChunks(), base.batchTokens(), base.protectedRecentTurns());
        return new ContextCompactor(pruner, hard, who);
    }

    // ---- clear ----

    /**
     * Starts a fresh conversation in the foreground agent. The agent forgets its turns, its pinned items, and its
     * pruned context, and the chat view of the foreground agent drops its lines. The transcript and the index keep
     * the earlier turns, so {@code /recall} still finds them. A setting that a turn produced, such as the todo list,
     * goes back to its start value. The chat view reports the clear when the event arrives.
     *
     * @return the result for the user, or an empty string when the chat view reports the clear
     */
    String clearForeground() {
        var target = screen.foreground();
        if (!target.equals(MAIN)) {
            var child = Agent.live(target).orElse(null);
            if (child == null) return "The agent of this view is not running. There is nothing to clear.";
            return child.clear() < 0 ? inTurn() : "";
        }
        if (main.agent().clear() < 0) return inTurn();
        if (toolState != null) toolState = new AgentState();
        attachments.clear();
        saveManifest("idle");
        return "";
    }

    private static String inTurn() {
        return "The agent is in a turn. Run /clear again when the turn ends.";
    }

    // ---- the backend of the slash commands ----

    private final class Backend implements org.github.lscoughlin.jeorje.ui.commands.CommandBackend {
        @Override
        public ModelRoles roles() { return roles; }

        @Override
        public void applyRoles(ModelRoles value) {
            roles = value;
            screen.status().model(value.defaultModel());
        }

        @Override
        public String compactNow() {
            return compactForeground();
        }

        @Override
        public String contextReport() {
            return InteractiveSession.this.contextReport();
        }

        @Override
        public String clearConversation() { return clearForeground(); }

        @Override
        public String pinLast() {
            var agent = main.agent();
            var turns = agent.turns();
            for (var i = turns.size() - 1; i >= 0; i--) {
                var messages = turns.get(i).messages();
                for (var j = messages.size() - 1; j >= 0; j--) {
                    var message = messages.get(j);
                    if (message.role() != Role.ASSISTANT) continue;
                    var sb = new StringBuilder();
                    message.content().forEach(c -> {
                        if (c instanceof Text t) sb.append(t.text());
                    });
                    if (!sb.isEmpty()) {
                        agent.pin(sb.toString());
                        return "Pinned the last assistant message.";
                    }
                }
            }
            return "There is no message to pin.";
        }

        @Override
        public List<String> recall(String query) {
            var hits = main.store().search(new HistoryQuery(query, Syntax.HYBRID, Filters.none(), 5));
            return hits.stream().map(h -> h.chunk().chunkId() + "  " + h.snippet().replace('\n', ' ')).toList();
        }

        @Override
        public org.github.lscoughlin.jeorje.ui.keys.KeyBindings keys() { return keys; }

        @Override
        public List<InstructionsLoader.Instruction> instructions() {
            var cfg = config.instructions();
            var loader = new InstructionsLoader(cfg.userFiles().stream().map(Path::of).toList(), cfg.maxBytes(),
                    cfg.maxTotalBytes());
            return loader.systemPromptFiles(cwd);
        }

        @Override
        public SkillRegistry skills() { return skills; }

        @Override
        public CommandRegistry commands() { return commandRegistry; }

        @Override
        public AgentTypeRegistry agentTypes() { return agentTypes; }

        @Override
        public boolean projectTrusted() { return trusted; }

        @Override
        public void trustProject() { InteractiveSession.this.trustProject(); }

        @Override
        public void revokeTrust() { InteractiveSession.this.revokeTrust(); }

        @Override
        public void sendPrompt(String prompt, String model) { InteractiveSession.this.sendPrompt(prompt, model); }

        @Override
        public String cancel(String name) { return InteractiveSession.this.cancel(name); }

        @Override
        public String back() {
            switcher.back();
            return "Foreground agent: " + screen.foreground() + ".";
        }

        @Override
        public void quit() { quit.countDown(); }
    }
}
