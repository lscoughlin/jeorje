package org.github.lscoughlin.jeorje.cli;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.agent.Agent;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.compaction.CompactionSettings;
import org.github.lscoughlin.jeorje.core.compaction.ContextCompactor;
import org.github.lscoughlin.jeorje.core.compaction.ModelRoles;
import org.github.lscoughlin.jeorje.core.compaction.Pruner;
import org.github.lscoughlin.jeorje.core.compaction.TermStats;
import org.github.lscoughlin.jeorje.core.config.ApiKey;
import org.github.lscoughlin.jeorje.core.config.ApiKeyResolver;
import org.github.lscoughlin.jeorje.core.config.ConfigLoader;
import org.github.lscoughlin.jeorje.core.config.ConfigOptions;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.config.LoadedConfig;
import org.github.lscoughlin.jeorje.core.config.SecretScrubber;
import org.github.lscoughlin.jeorje.core.instructions.InstructionsLoader;
import org.github.lscoughlin.jeorje.core.instructions.PromptInstructions;
import org.github.lscoughlin.jeorje.core.instructions.Scopes;
import org.github.lscoughlin.jeorje.core.instructions.SkillRegistry;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.diff.TextDiff;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.permissions.AuditLog;
import org.github.lscoughlin.jeorje.core.permissions.SkillGrantPolicy;
import org.github.lscoughlin.jeorje.core.permissions.ToolPermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.Embedder;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.session.Session;
import org.github.lscoughlin.jeorje.core.session.SessionManifest;
import org.github.lscoughlin.jeorje.core.session.SessionStore;
import org.github.lscoughlin.jeorje.core.session.TranscriptReader;
import org.github.lscoughlin.jeorje.core.session.ScrubbingContextStore;
import org.github.lscoughlin.jeorje.core.session.TranscriptWriter;
import org.github.lscoughlin.jeorje.core.subagent.SubagentLimits;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.core.state.AgentState;
import org.github.lscoughlin.jeorje.index.embed.OnnxEmbedder;
import org.github.lscoughlin.jeorje.index.store.LuceneContextStore;
import org.github.lscoughlin.jeorje.llm.OpenAiCompatibleClient;
import org.github.lscoughlin.jeorje.llm.OpenAiSettings;
import org.github.lscoughlin.jeorje.tools.agents.AgentTools;
import org.github.lscoughlin.jeorje.tools.diff.DiffTool;
import org.github.lscoughlin.jeorje.tools.diff.PatchTool;
import org.github.lscoughlin.jeorje.tools.files.DirectoryContext;
import org.github.lscoughlin.jeorje.tools.files.EditTool;
import org.github.lscoughlin.jeorje.tools.files.FrontMatter;
import org.github.lscoughlin.jeorje.tools.files.NestedInstructions;
import org.github.lscoughlin.jeorje.tools.files.ReadTool;
import org.github.lscoughlin.jeorje.tools.files.WriteTool;
import org.github.lscoughlin.jeorje.tools.memory.RecallTool;
import org.github.lscoughlin.jeorje.tools.memory.TodoTool;
import org.github.lscoughlin.jeorje.tools.registry.ToolRegistry;
import org.github.lscoughlin.jeorje.tools.registry.CommandTool;
import org.github.lscoughlin.jeorje.tools.search.GlobTool;
import org.github.lscoughlin.jeorje.tools.skills.SkillTool;
import org.github.lscoughlin.jeorje.tools.search.GrepTool;
import org.github.lscoughlin.jeorje.tools.search.LsTool;
import org.github.lscoughlin.jeorje.tools.shell.BashTool;
import org.github.lscoughlin.jeorje.tools.shell.ShellSettings;
import org.github.lscoughlin.jeorje.tools.web.WebFetchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the objects of one run: config, API key, model client, embedder, context store, session
 * store, policy, tool registry, instructions, and the agent.
 */
public final class Wiring {

    private static final Logger log = LoggerFactory.getLogger(Wiring.class);

    /** The context window of a model, from {@code context.window} and {@code context.windows}. */
    public static int contextWindow(LoadedConfig loaded, String model) {
        return ContextWindows.resolve(loaded, model);
    }

    private Wiring() {}

    /** The working directory: {@code --dir} or the current directory. */
    public static Path workDir(GlobalOptions options, CliEnvironment env) {
        return (options.dir() == null ? env.cwd() : options.dir()).toAbsolutePath().normalize();
    }

    /** Loads the config with the flags of the command line. */
    public static LoadedConfig loadConfig(GlobalOptions options, CliEnvironment env) {
        var flags = new LinkedHashMap<String, Object>();
        if (options.model() != null) flags.put("models.default", options.model());
        if (options.baseUrl() != null) flags.put("llm.baseUrl", options.baseUrl());
        if (!options.peers().isEmpty()) {
            flags.put("swarm.wka", List.copyOf(options.peers()));
            flags.put("swarm.enabled", true);
        }
        if (options.swarmToken() != null) flags.put("swarm.token", options.swarmToken());
        if (options.noMouse()) flags.put("ui.mouse.enabled", false);
        var configOptions = new ConfigOptions(env.home(), workDir(options, env), env.env(), flags,
                options.configFile() == null ? null : options.configFile().toAbsolutePath(), options.trust());
        return ConfigLoader.load(configOptions);
    }

    /** Installs logging. The run mode logs to standard error. */
    public static void configureLogging(JeorjeConfig config, CliEnvironment env, boolean toStderr) {
        if (!env.configureLogging()) return;
        var scrubber = new ApiKeyResolver(env.env()).scrubber(config);
        LoggingSetup.install(config.logging().level(), Path.of(config.logging().file()), toStderr, scrubber);
    }

    /** Prints the warnings for the unattended modes. */
    public static void warnUnattended(GlobalOptions options, CliEnvironment env) {
        if (options.yolo()) {
            var text = "WARNING: --yolo turns off all permission checks. Every tool call runs without a check.";
            env.err().println(text);
            log.atWarn().log(() -> text);
        } else if (options.yes()) {
            var text = "WARNING: --yes approves all permission prompts. Rules that deny a call still apply.";
            env.err().println(text);
            log.atWarn().log(() -> text);
        }
    }

    /** The embedder: the hook, or the ONNX model, or null for text-only search. */
    public static Embedder embedder(JeorjeConfig config, CliEnvironment env) {
        if (!config.index().embeddings()) return null;
        if (env.embedderFactory() != null) return env.embedderFactory().apply(config).orElse(null);
        var loaded = OnnxEmbedder.tryLoad(Path.of(config.index().modelCache()));
        if (loaded.isEmpty()) {
            log.atWarn().log(() -> "Embedding model not available. The index uses text search only.");
            return null;
        }
        return loaded.get();
    }

    /** The API key, or empty. */
    public static Optional<ApiKey> apiKey(JeorjeConfig config, CliEnvironment env) {
        return new ApiKeyResolver(env.env()).resolve(config);
    }

    public static LlmClient llmClient(JeorjeConfig config, CliEnvironment env, String model) {
        var key = apiKey(config, env);
        if (env.llmFactory() != null) return env.llmFactory().create(config, model, key.orElse(null));
        if (key.isEmpty()) {
            var names = config.llm().apiKeyEnv().isEmpty() ? List.of(ApiKeyResolver.DEFAULT_ENV)
                    : config.llm().apiKeyEnv();
            throw new CliException("No API key found. Set the environment variable " + String.join(" or ", names) + ".");
        }
        return new OpenAiCompatibleClient(OpenAiSettings.fromConfig(config, model, key.get()));
    }

    /**
     * The folders of the skills that live outside the project: the user folders, the folders of {@code skills.dirs}
     * and {@code agents.dirs}, and the folders of {@code skills.compat}. The path sandbox of every agent has them
     * as read-only directories, so an agent can read the files of a skill but cannot write them. The folders in the
     * project are in the workspace already.
     */
    public static List<Path> skillFolders(JeorjeConfig config, Path home, Path cwd) {
        if (!config.skills().enabled()) return List.of();
        var bases = new ArrayList<Path>();
        config.skills().dirs().forEach(d -> bases.add(cwd.resolve(d)));
        config.agents().dirs().forEach(d -> bases.add(cwd.resolve(d)));
        var compat = config.skills().compat().stream().map(d -> cwd.resolve(d)).toList();
        var out = new ArrayList<Path>();
        for (var root : Scopes.roots("skills", home, cwd, false, bases, compat)) out.add(root.dir());
        return List.copyOf(out);
    }

    /** The permission policy for a session. */
    public static ToolPermissionPolicy policy(JeorjeConfig config, GlobalOptions options, CliEnvironment env,
                                              Path cwd, String sessionId, SecretScrubber scrubber) {
        var extra = config.permissions().extraDirectories().stream().map(Path::of).toList();
        return ToolPermissionPolicy.builder(cwd)
                .flags(options.yes(), options.yolo())
                .configRules(config.permissions().rules())
                .extraDirectories(extra)
                .readOnlyDirectories(skillFolders(config, env.home(), cwd))
                .webfetch(config.permissions().webfetch().allow(), config.permissions().webfetch().deny())
                .scrubber(scrubber)
                .audit(AuditLog.forSession(cwd, sessionId, scrubber, Clock.systemUTC()))
                .build();
    }

    /** The tools of the agent as a registry, and the shared state of the file tools. */
    public static ToolRegistry registry(JeorjeConfig config, PermissionPolicy policy,
                                        org.github.lscoughlin.jeorje.core.permissions.NetworkPolicy network,
                                        AgentState state, String sessionId) {
        return registry(config, policy, network, state, sessionId, false);
    }

    /**
     * The tools of the agent as a registry. With {@code yolo}, {@code webfetch} has no domain lists and no
     * address blocks, as the design says for {@code --yolo}.
     */
    public static ToolRegistry registry(JeorjeConfig config, PermissionPolicy policy,
                                        org.github.lscoughlin.jeorje.core.permissions.NetworkPolicy network,
                                        AgentState state, String sessionId, boolean yolo) {
        return registry(config, policy, network, state, sessionId, yolo, null);
    }

    /** The tools of the agent as a registry, with the tools of the MCP servers when {@code mcp} is not null. */
    public static ToolRegistry registry(JeorjeConfig config, PermissionPolicy policy,
                                        org.github.lscoughlin.jeorje.core.permissions.NetworkPolicy network,
                                        AgentState state, String sessionId, boolean yolo,
                                        org.github.lscoughlin.jeorje.tools.mcp.McpManager mcp) {
        var builtInNames = BUILT_IN_NAMES;
        var read = config.tools().read();
        var nestedOn = config.instructions().enabled() && read.directoryDocs().contains("AGENTS.md");
        var nested = nestedOn ? new NestedInstructions(config.instructions().maxBytes()) : null;
        // The nested AGENTS.md files come through NestedInstructions, in full, once for each agent.
        var docs = read.directoryDocs().stream().filter(d -> !d.equals("AGENTS.md")).toList();
        var dirSettings = new DirectoryContext.Settings(docs, read.directoryDocChars(),
                read.directoryDocFallbackLines(),
                new FrontMatter.Limits(read.frontMatterScanLines(), read.frontMatterMaxLines()));
        var shell = ShellSettings.defaults().withShell(List.of(config.tools().bash().shell(), "-c"));
        var diff = config.tools().diff();
        var diffDefaults = TextDiff.Options.defaults().withContext(diff.context()).withMaxLines(diff.maxLines());
        var patch = config.tools().patch();
        var search = new org.github.lscoughlin.jeorje.tools.search.SearchOptions(
                new java.util.HashSet<>(config.tools().search().skipDirs()), config.tools().search().skipFiles());
        return ToolRegistry.builder()
                .register(new ReadTool(policy, state, dirSettings, nested))
                .register(new WriteTool(policy, state, nested))
                .register(new EditTool(policy, state, nested))
                .register(new PatchTool(policy, state, patch.fuzz(), patch.conflicts(), PatchTool.defaultOps()))
                .register(new DiffTool(policy, diffDefaults, diff.algorithm()))
                .register(new GlobTool(policy, search))
                .register(new GrepTool(policy, search))
                .register(new LsTool(policy, search))
                .register(new BashTool(policy, shell, config.tools().bash().timeoutSeconds()))
                .register(yolo
                        ? new WebFetchTool(new org.github.lscoughlin.jeorje.core.permissions.NetworkPolicy(
                                List.of(), List.of()), true, WebFetchTool.DEFAULT_MAX_BYTES, Duration.ofSeconds(30))
                        : new WebFetchTool(network, false, WebFetchTool.DEFAULT_MAX_BYTES, Duration.ofSeconds(30)))
                .register(new RecallTool(sessionId, id -> id))
                .register(new TodoTool())
                .registerAll(extensionTools(config, builtInNames))
                .registerAll(mcp == null ? List.<Tool>of() : mcp.tools())
                .build();
    }

    /**
     * Starts the MCP servers of the config and returns the manager, or null when there is nothing to start: no
     * enabled server, or {@code --no-mcp}. A server that fails to start is reported in the status, not thrown.
     */
    public static org.github.lscoughlin.jeorje.tools.mcp.McpManager mcp(
            JeorjeConfig config, GlobalOptions options,
            org.github.lscoughlin.jeorje.core.permissions.NetworkPolicy network, Path cwd,
            java.util.Map<String, String> environment) {
        if (options.noMcp() || config.mcp().servers().values().stream().noneMatch(s -> s.enabled())) return null;
        var reserved = new java.util.HashSet<>(BUILT_IN_NAMES);
        extensionTools(config, BUILT_IN_NAMES).forEach(t -> reserved.add(t.definition().name()));
        var manager = new org.github.lscoughlin.jeorje.tools.mcp.McpManager(config.mcp().servers(),
                org.github.lscoughlin.jeorje.tools.mcp.McpManager.ClientFactory.standard(network, cwd, environment),
                reserved);
        manager.connect();
        return manager;
    }

    /** The text of the {@code /mcp} command. */
    public static String mcpReport(org.github.lscoughlin.jeorje.tools.mcp.McpManager mcp, JeorjeConfig config,
                                   boolean disabledByFlag) {
        if (disabledByFlag) return "MCP is off: the session started with --no-mcp.";
        if (config.mcp().servers().isEmpty()) return "No MCP servers are configured. Set mcp.servers in the config.";
        if (mcp == null) return "No MCP server is enabled.";
        var sb = new StringBuilder("MCP servers\n");
        for (var status : mcp.status()) {
            sb.append("  ").append(status.name()).append(": ").append(status.detail()).append('\n');
            status.tools().forEach(t -> sb.append("    ").append(t).append('\n'));
        }
        return sb.toString().stripTrailing();
    }

    private static final java.util.Set<String> BUILT_IN_NAMES = java.util.Set.of("read", "write", "edit", "patch",
            "diff", "glob", "grep", "ls", "bash", "webfetch", "recall", "todo", "advisor", "ask_user", "skill");

    /**
     * The command tools of {@code tools.extensions}. An entry that is not valid, or that has the name of
     * another tool, is skipped with a warning.
     */
    public static List<Tool> extensionTools(JeorjeConfig config, java.util.Set<String> taken) {
        var out = new ArrayList<Tool>();
        var names = new java.util.HashSet<>(taken);
        for (var entry : config.tools().extensions()) {
            try {
                var tool = CommandTool.fromConfig(entry);
                var name = tool.definition().name();
                if (!names.add(name)) {
                    log.atWarn().log(() -> "Extension tool " + name + " is skipped: the name is already in use.");
                    continue;
                }
                out.add(tool);
            } catch (RuntimeException e) {
                log.atWarn().log(() -> "Extension tool is skipped: " + e.getMessage());
            }
        }
        return List.copyOf(out);
    }

    /**
     * The skills of the session. The project skills load only in a trusted project. With {@code skills.enabled:
     * false} the registry is empty.
     */
    public static SkillRegistry skills(JeorjeConfig config, Path home, Path cwd, boolean trusted) {
        if (!config.skills().enabled()) return SkillRegistry.scan(List.of());
        var bases = new ArrayList<Path>();
        config.skills().dirs().forEach(d -> bases.add(Path.of(d)));
        config.agents().dirs().forEach(d -> bases.add(Path.of(d)));
        var compat = config.skills().compat().stream().map(Path::of).toList();
        return SkillRegistry.scan(Scopes.roots("skills", home, cwd, trusted, bases, compat));
    }

    /** The names of the skills that a {@code DENY} rule for the {@code skill} tool hides from the model. */
    public static java.util.Set<String> hiddenSkills(SkillRegistry registry, PermissionPolicy policy) {
        var hidden = new java.util.HashSet<String>();
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        for (var skill : registry.skills()) {
            try {
                var input = mapper.createObjectNode().put("name", skill.name());
                if (policy.check(new ToolUse("skill-visible", SkillTool.NAME, input))
                        == PermissionPolicy.Decision.DENY) {
                    hidden.add(skill.name());
                }
            } catch (RuntimeException e) {
                log.atDebug().log(() -> "Cannot check the skill rule for " + skill.name() + ": " + e);
            }
        }
        return hidden;
    }

    /** The skills that the model may see: not hidden by the flag in the file, by a DENY rule, or by the config. */
    public static List<org.github.lscoughlin.jeorje.core.instructions.Skill> visibleSkills(
            SkillRegistry registry, PermissionPolicy policy) {
        return registry.modelVisible(hiddenSkills(registry, policy));
    }

    /**
     * The instructions of the system prompt: the AGENTS.md text and the skill catalog. The result reads the files
     * again on each call.
     *
     * @param skills the skills for the catalog, read on each call
     */
    public static PromptInstructions promptInstructions(JeorjeConfig config, Path cwd,
                                                        java.util.function.Supplier<List<org.github.lscoughlin.jeorje.core.instructions.Skill>> skills) {
        var cfg = config.instructions();
        var loader = cfg.enabled() ? new InstructionsLoader(cfg.userFiles().stream().map(Path::of).toList(),
                cfg.maxBytes(), cfg.maxTotalBytes()) : null;
        return new PromptInstructions(loader, cwd, config.skills().enabled() ? skills : null,
                config.skills().maxListChars());
    }

    /** The result of a tool without secrets. The error flag and the updates stay. */
    public static ToolResultData scrubbed(ToolResultData result, SecretScrubber scrubber) {
        if (scrubber == null || result == null) return result;
        var clean = scrubber.scrub(result.output());
        return clean.equals(result.output()) ? result : result.withOutput(clean);
    }

    /** Wraps the registry tools as agent tools. The wrapper keeps the shared file state current. */
    static List<Tool> adapt(ToolRegistry registry, AgentState state) {
        return adapt(registry, state, null);
    }

    /**
     * Wraps the registry tools as agent tools. The wrapper keeps the shared file state current. If a scrubber is
     * given, the wrapper removes secrets from each tool output, so a key that a tool prints never reaches the
     * chat, the transcript, the index, or the model.
     */
    static List<Tool> adapt(ToolRegistry registry, AgentState state, SecretScrubber scrubber) {
        var out = new ArrayList<Tool>();
        for (var name : registry.names()) {
            var tool = registry.find(name).orElseThrow();
            out.add(new Tool() {
                @Override
                public org.github.lscoughlin.jeorje.core.model.ToolDefinition definition() {
                    return tool.definition();
                }

                @Override
                public boolean readOnly() {
                    return tool.readOnly();
                }

                @Override
                public ToolResultData execute(tools.jackson.databind.JsonNode input, ToolContext context) {
                    var result = scrubbed(registry.execute(new ToolUse("call", name, input), context), scrubber);
                    if (!result.updates().isEmpty()) state.apply(result.updates());
                    return result;
                }
            });
        }
        return out;
    }

    /** The system-prompt instructions from the AGENTS.md files. */
    public static String instructions(JeorjeConfig config, Path cwd) {
        var cfg = config.instructions();
        if (!cfg.enabled()) return "";
        var loader = new InstructionsLoader(cfg.userFiles().stream().map(Path::of).toList(), cfg.maxBytes(),
                cfg.maxTotalBytes());
        return loader.systemPrompt(cwd);
    }

    /** The open objects of one session. */
    public static final class Runtime implements AutoCloseable {
        private final LoadedConfig loaded;
        private final Session session;
        private final SessionStore sessions;
        private final ContextStore store;
        private final Embedder embedder;
        private final TranscriptWriter transcript;
        private final Agent agent;
        private final List<String> errors;
        private final SwarmBridge swarm;
        private final SubagentRuntime subagents;
        private final org.github.lscoughlin.jeorje.tools.mcp.McpManager mcp;

        Runtime(LoadedConfig loaded, Session session, SessionStore sessions, ContextStore store, Embedder embedder,
                TranscriptWriter transcript, Agent agent, List<String> errors, SwarmBridge swarm,
                SubagentRuntime subagents, org.github.lscoughlin.jeorje.tools.mcp.McpManager mcp) {
            this.mcp = mcp;
            this.swarm = swarm;
            this.subagents = subagents;
            this.loaded = loaded;
            this.session = session;
            this.sessions = sessions;
            this.store = store;
            this.embedder = embedder;
            this.transcript = transcript;
            this.agent = agent;
            this.errors = errors;
        }

        public LoadedConfig loaded() { return loaded; }

        public Session session() { return session; }

        public SessionStore sessions() { return sessions; }

        public ContextStore store() { return store; }

        public Agent agent() { return agent; }

        /** The swarm bridge, or null when the swarm is off. */
        public SwarmBridge swarm() { return swarm; }

        /** The MCP manager, or null when no MCP server runs. */
        public org.github.lscoughlin.jeorje.tools.mcp.McpManager mcp() { return mcp; }

        /** The subagent runtime, or null when the swarm is off. */
        public SubagentRuntime subagents() { return subagents; }

        /** Error events that the agent published. */
        public List<String> errors() { return List.copyOf(errors); }

        /** Writes the manifest after a turn. */
        public Session saveManifest(String runState) {
            var manifest = agent.recordInto(session.manifest()
                    .withAgents(List.of(new SessionManifest.AgentNode(agent.id().value(), null, "root")))
                    .withUpdate(Clock.systemUTC().instant(), agent.turns().size(), runState));
            var next = session.with(manifest);
            next.save();
            return next;
        }

        @Override
        public void close() {
            agent.close();
            if (swarm != null) closeQuietly(swarm);
            if (subagents != null) closeQuietly(subagents);
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

    /** Builds everything for a new session. */
    public static Runtime open(LoadedConfig loaded, GlobalOptions options, CliEnvironment env) {
        var config = loaded.config();
        var cwd = workDir(options, env);
        var sessions = new SessionStore(cwd);
        var session = sessions.create(config);
        return open(loaded, options, env, sessions, session);
    }

    /**
     * Builds everything for an existing session and restores the agent: the finished turns, the pinned items, and
     * the pruned working context. The next request equals the request that the earlier run would have made.
     */
    public static Runtime openResumed(LoadedConfig loaded, GlobalOptions options, CliEnvironment env,
                                      SessionStore sessions, Session session) {
        var root = rootAgentOf(session.manifest());
        var runtime = open(loaded, options, env, sessions, session, root);
        runtime.agent().restore(session.manifest(), TranscriptReader.read(session.transcriptPath()), root);
        return runtime;
    }

    /** The id of the agent of the user in a manifest, or {@code root} when the manifest names none. */
    public static AgentId rootAgentOf(SessionManifest manifest) {
        return manifest.agents().stream().filter(a -> a.parentId() == null).findFirst()
                .map(a -> new AgentId(a.id())).orElse(new AgentId("root"));
    }

    /** Builds everything for a given session. */
    public static Runtime open(LoadedConfig loaded, GlobalOptions options, CliEnvironment env,
                               SessionStore sessions, Session session) {
        return open(loaded, options, env, sessions, session, new AgentId("root"));
    }

    private static Runtime open(LoadedConfig loaded, GlobalOptions options, CliEnvironment env,
                                SessionStore sessions, Session session, AgentId id) {
        var config = loaded.config();
        var cwd = workDir(options, env);
        var resolver = new ApiKeyResolver(env.env());
        var scrubber = resolver.scrubber(config);
        var roles = ModelRoles.from(config.models());

        var llm = llmClient(config, env, roles.defaultModel());
        var embedder = embedder(config, env);
        var scrubbing = new ScrubbingContextStore(
                LuceneContextStore.open(session.indexDir(), embedder, LuceneContextStore.Options.defaults().withUserBoost(config.index().userBoost())),
                scrubber);
        ContextStore store = scrubbing;
        var basePolicy = policy(config, options, env, cwd, session.id(), scrubber);
        var grants = new SkillGrantPolicy(basePolicy, config.skills().honorAllowedTools());
        PermissionPolicy policy = grants;
        var state = new AgentState();
        var mcp = mcp(config, options, basePolicy.network(), cwd, env.env());
        var registry = registry(config, grants, basePolicy.network(), state, session.id(), options.yolo(), mcp);
        var tools = new ArrayList<>(adapt(registry, state, scrubber));
        var window = contextWindow(loaded, roles.defaultModel());
        var skillRegistry = skills(config, env.home(), cwd, loaded.trusted());
        var visibleSkills = visibleSkills(skillRegistry, grants);
        var skillTool = SkillTool.create(visibleSkills, grants::skillLoaded);
        skillTool.ifPresent(tools::add);
        var advisor = new AdvisorSupport(roles::advisorSet, () -> llmClient(config, env, roles.advisor()));
        if (roles.advisorToolEnabled()) tools.add(advisor.slot());

        var settings = new AgentSettings(session.id(), cwd, window, config.context().budgetPercent(),
                config.llm().maxOutputTokens(), config.context().recentTurns(), config.agents().stepLimit(),
                null, config.prompts().system(), "", null)
                .withInstructionsSource(promptInstructions(config, cwd, () -> visibleSkills)::text)
                .withMaxProtectedTokens(config.skills().maxProtectedTokens());

        var errors = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var transcript = new TranscriptWriter(session.transcriptPath(), scrubber);
        var bridgeRef = new java.util.concurrent.atomic.AtomicReference<SwarmBridge>();
        java.util.function.Consumer<AgentEvent> onEvent = event -> {
            switch (event) {
                case AgentEvent.Error e -> {
                    if (event.agent().equals(id)) errors.add(scrubber.scrub(e.message()));
                }
                case AgentEvent.ConfirmationRequested c -> {
                    // No user can answer in the run mode. A call that needs approval is denied.
                    env.err().println("Denied: " + c.toolUse().tool() + " needs approval. Use --yes to approve.");
                    log.atWarn().log(() -> "denied " + c.toolUse().tool() + ": no user to ask");
                    Agent.live(c.agent()).ifPresent(a -> a.confirm(c.toolUse().id(), false));
                }
                default -> { }
            }
            var bridge = bridgeRef.get();
            if (bridge != null) bridge.onAgentEvent(event);
        };
        SwarmBridge bridge = null;
        SubagentRuntime subagents = null;
        var rootTools = List.copyOf(tools);
        if (SwarmBridge.wanted(config)) {
            // The swarm is on: the run mode gets the subagent runtime, so a peer can delegate a task to it and
            // the agent can delegate a task to a peer.
            bridge = SwarmBridge.forConfig(config, env.home(), cwd, env.env(), text -> log.atInfo().log(() -> text), null);
            bridgeRef.set(bridge);
            final var swarm = bridge;
            var builder = SubagentRuntime.builder(session.id(), settings, model -> llmClient(config, env,
                            model == null || model.isBlank() ? roles.defaultModel() : model))
                    .store(store).transcript(transcript::writeTurn).agentEvents(onEvent)
                    .pruneListener((child, chunks) -> skillTool.ifPresent(t -> t.releasePruned(child.value(), chunks)))
                    .events(swarm::onSubagentEvent).remote(swarm)
                    .limits(SubagentLimits.defaults().withMaxModelCalls(config.subagents().stepLimit()));
            subagents = builder.build();
            tools.addAll(PeerSpawnTool.wrap(AgentTools.install(subagents, session.id()), subagents));
        }
        var self = new java.util.concurrent.atomic.AtomicReference<Agent>();
        var agent = new Agent(id, settings, advisor.tap(llm), tools, policy, store, transcript::writeTurn,
                onEvent, Clock.systemUTC());
        self.set(agent);
        skillTool.ifPresent(t -> agent.withPruneListener(chunks -> t.releasePruned(id.value(), chunks)));
        if (bridge != null) {
            var host = subagents;
            host.registerRoot(id, "root", new CancellationToken(), rootTools, policy, agent::submit,
                    () -> self.get().turns().size() + 1);
            bridge.attach(host, rootTools, policy, agent::submit, () -> store);
            bridge.start();
        }

        var compactorClient = roles.pruningModel().map(m -> llmClient(config, env, m)).orElse(null);
        var compaction = config.context().compaction();
        var pruner = new Pruner(compactorClient, store, TermStats.of(store, scrubbing::appendedCount),
                new Pruner.Config(compaction.levels(), compaction.batchChunks(), compaction.batchTokens(),
                        compaction.maxKeywords(), config.prompts().compaction(), cwd));
        agent.withCompactor(new ContextCompactor(pruner,
                CompactionSettings.from(config.context(), window), id));
        log.atInfo().log(() -> "session " + session.id() + " opened in " + cwd + " with model "
                + roles.defaultModel() + (embedder == null ? " (text-only index)" : ""));
        return new Runtime(loaded, session, sessions, store, embedder, transcript, agent, errors, bridge, subagents, mcp);
    }
}
