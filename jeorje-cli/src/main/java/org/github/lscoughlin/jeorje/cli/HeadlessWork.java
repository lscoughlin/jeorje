package org.github.lscoughlin.jeorje.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.cli.interactive.GatedPolicy;
import org.github.lscoughlin.jeorje.core.agent.Agent;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.compaction.CompactionSettings;
import org.github.lscoughlin.jeorje.core.compaction.ContextCompactor;
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
import org.github.lscoughlin.jeorje.core.implement.ImplementReport;
import org.github.lscoughlin.jeorje.core.implement.ImplementRequest;
import org.github.lscoughlin.jeorje.core.implement.PlanFiles;
import org.github.lscoughlin.jeorje.core.implement.RunStateStore;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.permissions.SkillGrantPolicy;
import org.github.lscoughlin.jeorje.core.permissions.ToolPermissionPolicy;
import org.github.lscoughlin.jeorje.core.plan.PlanMode;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.Embedder;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.session.Session;
import org.github.lscoughlin.jeorje.core.session.ScrubbingContextStore;
import org.github.lscoughlin.jeorje.core.session.SessionStore;
import org.github.lscoughlin.jeorje.core.session.TranscriptWriter;
import org.github.lscoughlin.jeorje.core.state.AgentState;
import org.github.lscoughlin.jeorje.core.subagent.SubagentLimits;
import org.github.lscoughlin.jeorje.core.worktree.GitRunner;
import org.github.lscoughlin.jeorje.core.worktree.WorktreeManager;
import org.github.lscoughlin.jeorje.index.store.LuceneContextStore;
import org.github.lscoughlin.jeorje.tools.memory.AdvisorTool;
import org.github.lscoughlin.jeorje.tools.memory.AskUserTool;
import org.github.lscoughlin.jeorje.tools.memory.UserQuestion;
import org.github.lscoughlin.jeorje.tools.memory.UserQuestionBroker;
import org.github.lscoughlin.jeorje.tools.plan.PlanSubmitTool;
import org.github.lscoughlin.jeorje.tools.shell.BashTool;
import org.github.lscoughlin.jeorje.tools.skills.SkillTool;
import org.github.lscoughlin.jeorje.tools.story.StoryTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The commands {@code /plan} and {@code /implement} with no interface. {@code jeorje run "/plan <goal>"},
 * {@code jeorje run "/implement <plan>"}, and {@code jeorje run --plan <path>} use it.
 *
 * <p>No user can answer a question or a prompt. A question of {@code ask_user} gets the first choice with
 * {@code --yes} or {@code --yolo}. Without them, the run stops, prints the question, and exits with code 2. A draft
 * plan needs {@code --yes} or {@code --yolo} for the approval. A tool call that needs approval is denied.
 */
public final class HeadlessWork implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(HeadlessWork.class);
    private static final AgentId MAIN = new AgentId("main");
    private static final Pattern STORY_ID = Pattern.compile("[A-Za-z]{1,4}[0-9]+");
    private static final Pattern COMMAND = Pattern.compile("^/(plan|implement)(?:\\s+(.*))?$", Pattern.DOTALL);

    /** The exit code when a question stopped the run. */
    public static final int EXIT_QUESTION = 2;

    /** True if the task text is a {@code /plan} or {@code /implement} command. */
    public static boolean isCommand(String task) {
        return task != null && COMMAND.matcher(task.strip()).matches();
    }

    /** Runs the command in the task text. */
    public static int runCommand(CliEnvironment env, GlobalOptions options, String task) {
        var m = COMMAND.matcher(task.strip());
        if (!m.matches()) throw new IllegalArgumentException("Not a command: " + task);
        var args = m.group(2) == null ? "" : m.group(2).strip();
        if (m.group(1).equals("implement")) return implement(env, options, args);
        if (args.isEmpty()) {
            env.err().println("Error: /plan needs a goal. Example: jeorje run \"/plan add rate limiting\"");
            return 1;
        }
        try (var work = new HeadlessWork(env, options)) {
            return work.plan(args);
        }
    }

    /** Implements the plan in a file. The story ids select the stories. */
    public static int runPlan(CliEnvironment env, GlobalOptions options, String planPath, String storyIds) {
        var args = planPath + (storyIds == null || storyIds.isBlank() ? "" : " " + storyIds.strip());
        return implement(env, options, args);
    }

    private static int implement(CliEnvironment env, GlobalOptions options, String args) {
        var tokens = args.isBlank() ? new String[0] : args.strip().split("\\s+");
        if (tokens.length == 1) {
            switch (tokens[0]) {
                case "status" -> {
                    return status(env, options);
                }
                case "pause", "resume", "stop" -> {
                    env.err().println("No run is active in this process. A run with no interface ends with its "
                            + "report. Use /implement status to see the state of the last run.");
                    return 1;
                }
                default -> { }
            }
        }
        try (var work = new HeadlessWork(env, options)) {
            return work.implement(tokens);
        }
    }

    /** Prints the state of the last run from the run state file of the newest session that has one. */
    static int status(CliEnvironment env, GlobalOptions options) {
        var cwd = Wiring.workDir(options, env);
        var sessions = new SessionStore(cwd);
        for (var session : sessions.list()) {
            var file = session.dir().resolve("implement.json");
            if (!Files.isRegularFile(file)) continue;
            var state = RunStateStore.file(file).load();
            if (state.isEmpty()) continue;
            var run = state.get();
            var sb = new StringBuilder("Plan " + run.plan() + ": " + run.status() + " (session " + session.id() + ")\n");
            sb.append(String.format("%-8s %-9s %-7s %-30s %s%n", "Story", "State", "Attempt", "Worker", "Branch"));
            for (var e : run.stories()) {
                sb.append(String.format("%-8s %-9s %-7d %-30s %s%n", e.id(), e.state(), e.attempt(),
                        nullToEmpty(e.worker()), nullToEmpty(e.branch())));
                if ("blocked".equals(e.state()) && e.reason() != null && !e.reason().isBlank()) {
                    sb.append("    reason: ").append(e.reason()).append('\n');
                }
            }
            env.out().print(sb);
            return 0;
        }
        env.out().println("No run.");
        return 0;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    // ---- instance ----

    private final CliEnvironment env;
    private final GlobalOptions options;
    private final LoadedConfig loaded;
    private final JeorjeConfig config;
    private final Path cwd;
    private final ModelRoles roles;
    private final SecretScrubber scrubber;
    private final SessionStore sessions;
    private Session session;
    private final Embedder embedder;
    private final ContextStore store;
    private final ScrubbingContextStore counting;
    private final TranscriptWriter transcript;
    private final ToolPermissionPolicy policy;
    private final SkillGrantPolicy grants;
    private final Map<String, LlmClient> clients = new ConcurrentHashMap<>();
    private final List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final boolean yes;
    private final AdvisorSupport advisor;
    private WorktreeManager worktrees;
    private ImplementOrchestrator orchestrator;
    private Agent agent;
    private volatile List<org.github.lscoughlin.jeorje.core.instructions.Skill> toolSkills = List.of();
    private volatile SkillTool skillTool;
    private org.github.lscoughlin.jeorje.tools.mcp.McpManager mcp;
    private boolean mcpStarted;
    private volatile UserQuestion stoppedBy;

    private HeadlessWork(CliEnvironment env, GlobalOptions options) {
        this.env = env;
        this.options = options;
        this.yes = options.yes() || options.yolo();
        loaded = Wiring.loadConfig(options, env);
        config = loaded.config();
        Wiring.configureLogging(config, env, true);
        Wiring.warnUnattended(options, env);
        loaded.warnings().forEach(w -> env.err().println("Warning: " + w));
        cwd = Wiring.workDir(options, env);
        roles = ModelRoles.from(config.models());
        scrubber = new ApiKeyResolver(env.env()).scrubber(config);
        clientFor(null);
        sessions = new SessionStore(cwd);
        session = sessions.create(config);
        embedder = Wiring.embedder(config, env);
        store = counting = new ScrubbingContextStore(
                LuceneContextStore.open(session.indexDir(), embedder, LuceneContextStore.Options.defaults()), scrubber);
        transcript = new TranscriptWriter(session.transcriptPath(), scrubber);
        policy = Wiring.policy(config, options, env, cwd, session.id(), scrubber);
        grants = new SkillGrantPolicy(policy, config.skills().honorAllowedTools());
        advisor = new AdvisorSupport(roles::advisorSet, () -> clientFor(roles.advisor()));
    }

    private LlmClient clientFor(String model) {
        var name = model == null || model.isBlank() ? roles.defaultModel() : model;
        return clients.computeIfAbsent(name, m -> Wiring.llmClient(config, env, m));
    }

    // ---- plan ----

    private int plan(String goal) {
        var mode = new PlanMode();
        var broker = new UserQuestionBroker(question -> onQuestion(question, brokerRef.get()));
        brokerRef.set(broker);
        var mainPolicy = new GatedPolicy(grants, mode, null);
        var tools = new LinkedHashMap<String, Tool>();
        baseTools().forEach(t -> tools.put(t.definition().name(), t));
        tools.put(PlanSubmitTool.NAME, new PlanSubmitTool(config.plan().dir(), mode,
                file -> env.err().println("Plan written: " + file)));
        tools.put(AskUserTool.NAME, new AskUserTool(broker));
        tools.put(AdvisorTool.NAME, advisor.slot());
        agent = new Agent(MAIN, settings(), advisor.tap(clientFor(null)), List.copyOf(tools.values()), mainPolicy,
                store, transcript::writeTurn, this::onAgentEvent, Clock.systemUTC());
        agent.withPruneListener(chunks -> {
            var skills = skillTool;
            if (skills != null) skills.releasePruned(MAIN.value(), chunks);
        });
        var compactorClient = roles.pruningModel().map(this::clientFor).orElse(null);
        var compaction = config.context().compaction();
        var pruner = new Pruner(compactorClient, store, TermStats.of(store, counting::appendedCount),
                new Pruner.Config(compaction.levels(), compaction.batchChunks(), compaction.batchTokens(),
                        compaction.maxKeywords(), config.prompts().compaction(), cwd));
        agent.withCompactor(new ContextCompactor(pruner,
                CompactionSettings.from(config.context(), Wiring.contextWindow(loaded, roles.defaultModel())), MAIN));
        mode.enter(goal);
        agent.planMode(mode.promptSection());
        saveManifest("running");
        agent.runTurn(goal);
        mode.done();
        agent.planMode(null);
        saveManifest("idle");

        var question = stoppedBy;
        if (question != null) {
            env.out().println("Question from the agent: " + question.question());
            question.choices().forEach(c -> env.out().println("  - " + c));
            env.err().println("The plan is not finished. Run again with --yes to answer with the first choice, or "
                    + "put the answer into the goal.");
            return EXIT_QUESTION;
        }
        var file = mode.submittedPlan();
        if (file.isPresent()) {
            env.out().println("Plan: " + file.get());
            return 0;
        }
        var answer = finalAnswer();
        if (!answer.isBlank()) env.out().println(answer);
        errors.forEach(e -> env.err().println("Error: " + e));
        env.err().println("No plan was written.");
        return 1;
    }

    private final AtomicReference<UserQuestionBroker> brokerRef = new AtomicReference<>();

    private void onQuestion(UserQuestion question, UserQuestionBroker broker) {
        if (yes) {
            var answer = question.choices().isEmpty() ? "Use your best judgment and go on."
                    : question.choices().getFirst();
            env.err().println("Question: " + question.question() + " Answer: " + answer);
            broker.answer(question.id(), answer);
            return;
        }
        stoppedBy = question;
        agent.cancel();
    }

    private String finalAnswer() {
        var turns = agent.turns();
        if (turns.isEmpty()) return "";
        var messages = turns.getLast().messages();
        for (var i = messages.size() - 1; i >= 0; i--) {
            var message = messages.get(i);
            if (message.role() != Role.ASSISTANT) continue;
            var sb = new StringBuilder();
            message.content().forEach(c -> {
                if (c instanceof Text t) sb.append(t.text());
            });
            if (!sb.isEmpty()) return sb.toString();
        }
        return "";
    }

    private void onAgentEvent(AgentEvent event) {
        switch (event) {
            case AgentEvent.Error e -> {
                if (event.agent().equals(MAIN)) errors.add(scrubber.scrub(e.message()));
                else log.atWarn().log(() -> event.agent() + ": " + e.message());
            }
            case AgentEvent.ConfirmationRequested c -> {
                env.err().println("Denied: " + c.toolUse().tool() + " needs approval. Use --yes to approve.");
                log.atWarn().log(() -> "denied " + c.toolUse().tool() + ": no user to ask");
                Agent.live(c.agent()).ifPresent(a -> a.confirm(c.toolUse().id(), false));
            }
            default -> { }
        }
    }

    // ---- implement ----

    private int implement(String[] tokens) {
        String ref = null;
        var only = new LinkedHashSet<String>();
        for (var i = 0; i < tokens.length; i++) {
            if (i == 0 && !STORY_ID.matcher(tokens[i]).matches()) ref = tokens[i];
            else only.add(tokens[i]);
        }
        var dir = cwd.resolve(config.plan().dir()).normalize();
        Path plan = null;
        if (ref != null) {
            var direct = cwd.resolve(ref);
            if (Files.isRegularFile(direct)) plan = direct;
        }
        if (plan == null) {
            var found = PlanFiles.find(dir, ref);
            if (found.isEmpty()) {
                env.err().println(ref == null ? "No plan with open stories in " + dir + "."
                        : "No plan named " + ref + " in " + dir + ".");
                return 1;
            }
            plan = found.get();
            if (ref == null) {
                var open = PlanFiles.openPlans(dir);
                if (open.size() > 1) {
                    env.err().println("More than one plan has open stories. Use /implement <plan>:");
                    open.forEach(p -> env.err().println("  " + p.getFileName()));
                    return 1;
                }
            }
        }
        openWorktrees();
        if (worktrees != null) {
            var status = worktrees.git().run(cwd, "status", "--porcelain");
            if (status.ok() && !status.trimmed().isEmpty()) {
                env.err().println("The working tree has uncommitted changes. The workers start from the last "
                        + "commit and do not see them.");
            }
        }
        buildOrchestrator();
        saveManifest("running");
        var report = orchestrator.run(new ImplementRequest(plan, only, yes));
        saveManifest("idle");
        env.out().print(report.render());
        return exitCode(report, plan);
    }

    private int exitCode(ImplementReport report, Path plan) {
        switch (report.status()) {
            case "done" -> {
                return 0;
            }
            case "not approved" -> env.err().println("The plan " + plan + " is a draft. Use --yes or --yolo to "
                    + "approve it.");
            case "error" -> env.err().println("Error: " + report.error());
            default -> { }
        }
        return 1;
    }

    private void openWorktrees() {
        if ("never".equals(config.worktree().isolation())) return;
        var git = GitRunner.process();
        var inside = git.run(cwd, "rev-parse", "--is-inside-work-tree");
        if (!inside.ok() || !"true".equals(inside.trimmed())) return;
        try {
            worktrees = new WorktreeManager(git, cwd, config.worktree(), text -> env.err().println(text));
            worktrees.processStopper(BashTool::stopProcessesIn);
            var report = worktrees.startup(List.of());
            if (!report.leftover().isEmpty()) {
                env.err().println("Leftover worktrees on disk: " + report.leftover());
            }
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "Worktrees are not available: " + e.getMessage());
            worktrees = null;
        }
    }

    private void buildOrchestrator() {
        var workerPolicy = new GatedPolicy(grants, null, this::askForWorker);
        var builder = ImplementOrchestrator.builder(session.id(), settings(), this::clientFor, config.implement())
                .store(store).transcript(transcript::writeTurn).worktrees(worktrees)
                .stateStore(RunStateStore.file(session.dir().resolve("implement.json")))
                .events(this::onImplementEvent).agentEvents(this::onAgentEvent)
                .orchestratorInput(input -> log.atDebug().log(() -> "orchestrator input: " + input.text()))
                .limits(limits()).policy(workerPolicy).workspacePolicies(this::policyForWorkspace)
                .approver(plan -> false)
                .baseTools(baseTools())
                .workerTools(StoryTools.factory(List.of()));
        if (roles.advisorSet() && "advisor".equals(config.implement().review())) {
            builder.reviewer(new AdvisorReviewer(clientFor(roles.advisor())));
        }
        builder.compactor(roles.pruningModel().map(this::clientFor).orElse(null));
        orchestrator = builder.build();
    }

    private PermissionPolicy.Decision askForWorker(AgentId who, ToolUse use) {
        var story = orchestrator == null ? java.util.Optional.<String>empty() : orchestrator.storyOf(who);
        env.err().println("Denied: " + story.orElse(who.value()) + " needs approval to run " + use.tool()
                + ". Use --yes to approve.");
        return PermissionPolicy.Decision.DENY;
    }

    private PermissionPolicy policyForWorkspace(PermissionPolicy parent, AgentId child, Path workspace) {
        var rooted = policy.rootedAt(child, workspace);
        var own = new GatedPolicy(new SkillGrantPolicy(rooted, config.skills().honorAllowedTools()), null,
                this::askForWorker);
        return action -> ToolPermissionPolicy.hasPath(action.input()) ? own.check(action) : parent.check(action);
    }

    private void onImplementEvent(ImplementEvent event) {
        var line = switch (event) {
            case ImplementEvent.RunStarted s ->
                    "IMPLEMENT: plan " + s.plan() + ", " + s.open() + " of " + s.stories() + " stories are open.";
            case ImplementEvent.StoryStarted s -> "IMPLEMENT: " + s.storyId() + " started (attempt " + s.attempt()
                    + ", worker " + s.worker() + ").";
            case ImplementEvent.StoryChanged c -> "IMPLEMENT: " + c.storyId() + " is " + c.state().label()
                    + (c.detail() == null || c.detail().isBlank() ? "" : ": " + c.detail());
            case ImplementEvent.TaskFinished t -> "IMPLEMENT: " + t.storyId() + " " + t.taskId() + " done.";
            case ImplementEvent.Reviewed r -> "IMPLEMENT: review of " + r.storyId() + " "
                    + (r.approved() ? "approved." : "found problems: " + r.problems());
            case ImplementEvent.Notice n -> n.text();
            case ImplementEvent.RunControl c -> "IMPLEMENT: " + c.action() + ".";
            case ImplementEvent.RunFinished f -> null;
        };
        if (line != null) env.err().println(line);
    }

    // ---- shared ----

    private AgentSettings settings() {
        return new AgentSettings(session.id(), cwd, Wiring.contextWindow(loaded, roles.defaultModel()),
                config.context().budgetPercent(), config.llm().maxOutputTokens(), config.context().recentTurns(),
                config.agents().stepLimit(), null, config.prompts().system(), "", null)
                .withInstructionsSource(Wiring.promptInstructions(config, cwd, () -> toolSkills)::text)
                .withMaxProtectedTokens(config.skills().maxProtectedTokens());
    }

    private SubagentLimits limits() {
        return SubagentLimits.defaults()
                .withAskTimeout(Duration.ofSeconds(config.subagents().askTimeoutSeconds()))
                .withMaxAsks(config.subagents().maxAsks())
                .withMaxModelCalls(config.subagents().stepLimit());
    }

    /** The tools of the coding agent as agent tools. */
    private List<Tool> baseTools() {
        var state = new AgentState();
        var registry = Wiring.registry(config, grants, policy.network(), state, session.id(), options.yolo(),
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
                    var result = Wiring.scrubbed(registry.execute(new ToolUse("call", name, input), context), scrubber);
                    if (!result.updates().isEmpty()) state.apply(result.updates());
                    return result;
                }
            });
        }
        var skills = Wiring.skills(config, env.home(), cwd, loaded.trusted());
        toolSkills = Wiring.visibleSkills(skills, grants);
        SkillTool.create(toolSkills, grants::skillLoaded).ifPresent(t -> {
            skillTool = t;
            out.add(t);
        });
        return out;
    }

    /** The MCP servers of the run. They start once, at the first use. */
    private synchronized org.github.lscoughlin.jeorje.tools.mcp.McpManager mcpManager() {
        if (!mcpStarted) {
            mcpStarted = true;
            mcp = Wiring.mcp(config, options, policy.network(), cwd, env.env());
        }
        return mcp;
    }

    /** Holds a tool call of a worker until its worktree exists. */
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

    private void saveManifest(String runState) {
        var manifest = session.manifest();
        if (agent != null) {
            manifest = agent.recordInto(manifest.withAgents(List.of(
                    new org.github.lscoughlin.jeorje.core.session.SessionManifest.AgentNode(agent.id().value(), null,
                            "root"))));
        }
        var turns = agent == null ? 0 : agent.turns().size();
        session = session.with(manifest.withUpdate(Clock.systemUTC().instant(), turns, runState));
        session.save();
    }

    @Override
    public void close() {
        try {
            if (orchestrator != null) {
                if (orchestrator.active()) orchestrator.stop();
                orchestrator.close();
            }
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "Cannot stop the run: " + e);
        }
        if (agent != null) agent.close();
        if (mcp != null) closeQuietly(mcp);
        closeQuietly(transcript);
        closeQuietly(store);
        if (embedder instanceof AutoCloseable c) closeQuietly(c);
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception e) {
            log.atWarn().log(() -> "close failed: " + e.getMessage());
        }
    }
}
