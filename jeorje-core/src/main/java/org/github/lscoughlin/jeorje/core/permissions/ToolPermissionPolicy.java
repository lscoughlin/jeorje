package org.github.lscoughlin.jeorje.core.permissions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.config.SecretScrubber;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The default {@link PermissionPolicy}. It applies rules, the path sandbox, shell command
 * checks, and the network policy. It writes the audit log. See the design document
 * "Permissions and Safety".
 */
public final class ToolPermissionPolicy implements PermissionPolicy {

    private static final Logger log = LoggerFactory.getLogger(ToolPermissionPolicy.class);

    /** How the policy treats ASK and the checks. */
    public enum Mode {
        /** Normal checks. */
        NORMAL,
        /** {@code --yes}: ASK becomes ALLOW. DENY and the sandbox stay. */
        YES,
        /** {@code --yolo}: every action is ALLOW. */
        YOLO
    }

    private static final Set<String> PLAN_TOOLS = Set.of(
            "read", "ls", "glob", "grep", "diff", "recall", "agent_status", "ask_user", "advisor",
            "todo", "plan_submit", "bash");

    private static final List<String> PATH_FIELDS = List.of("path", "file_path", "filePath", "a", "b");

    private final Mode mode;
    private final boolean planMode;
    private final Path planDirectory;
    private final PathSandbox sandbox;
    private final NetworkPolicy network;
    private final List<Rule> rules;
    private final List<Rule> sessionRules = new CopyOnWriteArrayList<>();
    private final SecretScrubber scrubber;
    private final AuditLog audit;
    private final AgentId agent;
    private final ToolPermissionPolicy parent;
    private final ToolPermissionPolicy ruleSource;

    private ToolPermissionPolicy(Builder b) {
        this.mode = b.mode;
        this.planMode = b.planMode;
        this.planDirectory = b.planDirectory == null ? null : PathSandbox.realOf(b.planDirectory);
        this.sandbox = b.sandbox;
        this.network = b.network;
        this.rules = List.copyOf(b.rules);
        this.scrubber = b.scrubber;
        this.audit = b.audit;
        this.agent = b.agent;
        this.parent = b.parent;
        this.ruleSource = b.ruleSource;
    }

    public static Builder builder(Path workspace) {
        return new Builder(workspace);
    }

    public Mode mode() {
        return mode;
    }

    public boolean planMode() {
        return planMode;
    }

    public NetworkPolicy network() {
        return network;
    }

    public PathSandbox sandbox() {
        return sandbox;
    }

    /** Saves an "always allow" (or other) rule for this session. */
    public void addSessionRule(Rule rule) {
        sessionRules.add(rule);
    }

    /**
     * Makes a policy for a subagent. The result is never wider than this policy: it takes the
     * strictest decision of its own rules and the rules of this policy. If {@code readOnly} is
     * true, the child runs in plan mode.
     */
    public ToolPermissionPolicy narrow(AgentId child, List<Rule> extraRules, boolean readOnly) {
        var b = new Builder(sandbox.workspace());
        b.mode = Mode.NORMAL;
        b.planMode = readOnly || planMode;
        b.planDirectory = planDirectory;
        b.sandbox = sandbox;
        b.network = network;
        b.rules.addAll(extraRules);
        b.scrubber = scrubber;
        b.audit = audit;
        b.agent = child;
        b.parent = this;
        return new ToolPermissionPolicy(b);
    }

    /**
     * Makes the policy of a subagent that works in its own directory, for example a worktree. The result has the
     * same mode, rules, plan mode, network policy, extra directories, and read-only directories as this policy, and
     * it shares the session rules of this policy. Its workspace is {@code workspace}. A path in the workspace of
     * this policy but outside {@code workspace} is outside the sandbox of the result, so the subagent cannot read
     * or write there. The result is never wider than this policy: it adds no allow rule and no directory.
     */
    public ToolPermissionPolicy rootedAt(AgentId child, Path workspace) {
        var b = new Builder(workspace);
        b.mode = mode;
        b.planMode = planMode;
        b.planDirectory = planDirectory;
        b.sandbox = sandbox.withWorkspace(workspace);
        b.network = network;
        b.rules.addAll(rules);
        b.scrubber = scrubber;
        b.audit = audit;
        b.agent = child;
        b.ruleSource = this;
        return new ToolPermissionPolicy(b);
    }

    @Override
    public Decision check(ToolUse action) {
        var result = evaluate(action);
        var decision = result.decision();
        var source = result.source();
        log.atDebug().log(() -> scrubber.scrub("permission " + action.tool() + " " + decision + " (" + source + ")"));
        if (audit != null) audit.record(agent, action, decision, source, "decided");
        return decision;
    }

    /** Records the answer of the user to an ASK. */
    public void recordUserDecision(ToolUse action, Decision decision) {
        if (audit != null) audit.record(agent, action, decision, "user", "decided");
    }

    /** Checks the target of a redirect of {@code webfetch}. */
    public Decision checkRedirect(String url) {
        if (mode == Mode.YOLO && !planMode) return Decision.ALLOW;
        var verdict = network.check(url);
        return mode == Mode.YES && verdict.decision() == Decision.ASK ? Decision.ALLOW : verdict.decision();
    }

    private record Result(Decision decision, String source) {}

    private Result evaluate(ToolUse action) {
        var own = evaluateOwn(action.tool(), action.input());
        if (parent == null) return own;
        var inherited = parent.evaluate(action);
        return inherited.decision().compareTo(own.decision()) > 0 ? inherited : own;
    }

    private Result evaluateOwn(String tool, JsonNode input) {
        if (planMode) {
            var plan = planDecision(tool, input);
            if (plan != null) return new Result(plan, "rule");
        }
        if (mode == Mode.YOLO) return new Result(Decision.ALLOW, "yolo");
        var decision = switch (tool) {
            case "bash" -> checkBash(input);
            case "webfetch" -> checkWebfetch(input);
            default -> checkGeneric(tool, input);
        };
        if (mode == Mode.YES && decision == Decision.ASK) decision = Decision.ALLOW;
        return new Result(decision, "rule");
    }

    /** Returns DENY for a forbidden action in plan mode, or null if plan mode lets it continue. */
    private Decision planDecision(String tool, JsonNode input) {
        if (!PLAN_TOOLS.contains(tool)) return Decision.DENY;
        if (tool.equals("plan_submit")) {
            var path = firstPath(input);
            if (planDirectory == null || path == null) return Decision.DENY;
            var real = PathSandbox.realOf(sandbox.workspace().resolve(path));
            return real.startsWith(planDirectory) ? null : Decision.DENY;
        }
        return null;
    }

    private List<Rule> allRules() {
        var all = new ArrayList<>(rules);
        all.addAll(sessionRules);
        if (ruleSource != null) all.addAll(ruleSource.sessionRulesNow());
        return all;
    }

    private List<Rule> sessionRulesNow() {
        var all = new ArrayList<>(sessionRules);
        if (ruleSource != null) all.addAll(ruleSource.sessionRulesNow());
        return all;
    }

    private Decision checkBash(JsonNode input) {
        var parts = ShellCommands.split(text(input, "command"));
        if (parts.isEmpty()) return Decision.ASK;
        var all = allRules();
        var result = Decision.ALLOW;
        for (var part : parts) {
            var ruled = Rule.resolve(all, "bash", List.of(part));
            var partDecision = ruled != null ? ruled
                    : ShellCommands.isReadOnly(part) ? Decision.ALLOW : Decision.ASK;
            result = Rule.strictest(result, partDecision);
        }
        return result;
    }

    private Decision checkWebfetch(JsonNode input) {
        var url = text(input, "url");
        var verdict = network.check(url);
        if (verdict.decision() == Decision.DENY) return Decision.DENY;
        var host = NetworkPolicy.hostOf(url);
        var ruled = host == null ? null : Rule.resolve(allRules(), "webfetch", List.of(host));
        return ruled != null ? ruled : verdict.decision();
    }

    private Decision checkGeneric(String tool, JsonNode input) {
        var all = allRules();
        var write = DefaultRules.WRITE_TOOLS.contains(tool);
        var subjects = new ArrayList<String>();
        var floor = Decision.ALLOW;
        for (var path : paths(input)) {
            var result = sandbox.check(path);
            var own = List.of(path, result.resolved().toString());
            subjects.addAll(own);
            switch (result.area()) {
                case WORKSPACE -> { }
                case READ_ONLY -> {
                    if (write) return Decision.DENY;
                }
                case OUTSIDE -> {
                    var allowedByRule = all.stream()
                            .anyMatch(r -> r.pattern() != null && r.matches(tool, own));
                    if (!allowedByRule) return Decision.DENY;
                }
            }
            if (result.sensitive()) floor = Rule.strictest(floor, Decision.ASK);
        }
        var ruled = Rule.resolve(all, tool, subjects.isEmpty() ? List.of("") : subjects);
        var base = ruled != null ? ruled : DefaultRules.forTool(tool);
        return Rule.strictest(base, floor);
    }

    /** True if the policy checks a path of this tool input: it has one of the path fields. */
    public static boolean hasPath(JsonNode input) {
        return !paths(input).isEmpty();
    }

    private static List<String> paths(JsonNode input) {
        var out = new ArrayList<String>();
        if (input == null) return out;
        for (var field : PATH_FIELDS) {
            var node = input.get(field);
            if (node != null && node.isString() && !node.asString().isBlank()) out.add(node.asString());
        }
        var list = input.get("paths");
        if (list != null && list.isArray()) {
            for (var element : list) {
                if (element.isString()) out.add(element.asString());
            }
        }
        return out;
    }

    private static String firstPath(JsonNode input) {
        var paths = paths(input);
        return paths.isEmpty() ? null : paths.get(0);
    }

    private static String text(JsonNode input, String field) {
        if (input == null) return "";
        var node = input.get(field);
        return node == null || !node.isString() ? "" : node.asString();
    }

    /** Builds a policy. */
    public static final class Builder {
        private Mode mode = Mode.NORMAL;
        private boolean planMode;
        private Path planDirectory;
        private PathSandbox sandbox;
        private NetworkPolicy network;
        private final List<Rule> rules = new ArrayList<>();
        private SecretScrubber scrubber = new SecretScrubber();
        private AuditLog audit;
        private AgentId agent = new AgentId("root");
        private ToolPermissionPolicy parent;
        private ToolPermissionPolicy ruleSource;
        private final Path workspace;
        private final List<Path> extra = new ArrayList<>();
        private final List<Path> readOnly = new ArrayList<>();
        private final List<Path> configDirs = new ArrayList<>();
        private List<String> allow = List.of();
        private List<String> deny = List.of();
        private NetworkPolicy.Resolver resolver;

        private Builder(Path workspace) {
            this.workspace = workspace;
        }

        public Builder mode(Mode mode) { this.mode = mode; return this; }

        /** The flags {@code --yes} and {@code --yolo}. If both are set, yolo wins. */
        public Builder flags(boolean yes, boolean yolo) {
            this.mode = yolo ? Mode.YOLO : yes ? Mode.YES : Mode.NORMAL;
            return this;
        }

        public Builder planMode(boolean planMode, Path planDirectory) {
            this.planMode = planMode;
            this.planDirectory = planDirectory;
            return this;
        }

        public Builder rules(List<Rule> rules) { this.rules.addAll(rules); return this; }

        public Builder configRules(List<JeorjeConfig.PermissionRule> rules) {
            rules.forEach(r -> this.rules.add(Rule.from(r)));
            return this;
        }

        public Builder extraDirectories(List<Path> dirs) { extra.addAll(dirs); return this; }

        public Builder readOnlyDirectories(List<Path> dirs) { readOnly.addAll(dirs); return this; }

        public Builder configDirectories(List<Path> dirs) { configDirs.addAll(dirs); return this; }

        public Builder webfetch(List<String> allow, List<String> deny) {
            this.allow = allow;
            this.deny = deny;
            return this;
        }

        public Builder resolver(NetworkPolicy.Resolver resolver) { this.resolver = resolver; return this; }

        public Builder scrubber(SecretScrubber scrubber) { this.scrubber = scrubber; return this; }

        public Builder audit(AuditLog audit) { this.audit = audit; return this; }

        public Builder agent(AgentId agent) { this.agent = agent; return this; }

        public ToolPermissionPolicy build() {
            sandbox = new PathSandbox(workspace, extra, readOnly, configDirs);
            network = resolver == null ? new NetworkPolicy(allow, deny) : new NetworkPolicy(allow, deny, resolver);
            return new ToolPermissionPolicy(this);
        }
    }
}
