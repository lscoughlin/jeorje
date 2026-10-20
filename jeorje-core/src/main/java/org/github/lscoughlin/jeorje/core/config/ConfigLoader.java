package org.github.lscoughlin.jeorje.core.config;

import java.io.IOException;
import java.nio.file.Files;
import org.github.lscoughlin.jeorje.core.instructions.TrustStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Loads the config layers, merges them, and returns an immutable {@link JeorjeConfig}.
 *
 * <p>Order, weakest first: defaults, user config, project config, the {@code --config} file,
 * environment variables, flags. In each directory the first file of {@code config.yaml},
 * {@code config.yml}, {@code config.json} wins. Maps merge key by key. Lists and scalars replace,
 * except {@code permissions.rules}, where the project rules add to the user rules so that a
 * project cannot drop a user {@code deny} rule.
 */
public final class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    static final List<String> FILE_NAMES = List.of("config.yaml", "config.yml", "config.json");

    private static final ObjectMapper YAML = YAMLMapper.builder().build();
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final ObjectMapper BINDER = JsonMapper.builder()
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final Set<String> ENUM_MODES = Set.of("append", "replace");

    private final ConfigOptions options;
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, ConfigSource> sources = new LinkedHashMap<>();
    private final SecretScrubber scrubber = new SecretScrubber();

    private ConfigLoader(ConfigOptions options) {
        this.options = options;
    }

    public static LoadedConfig load(ConfigOptions options) {
        return new ConfigLoader(options).run();
    }

    /** Converts a complete tree into the record tree. */
    static JeorjeConfig bind(Map<String, Object> tree) {
        try {
            return BINDER.convertValue(tree, JeorjeConfig.class);
        } catch (JacksonException e) {
            throw new ConfigException("Invalid config value: " + e.getOriginalMessage());
        }
    }

    private LoadedConfig run() {
        var home = options.home();
        var cwd = options.cwd();
        var tree = ConfigDefaults.tree(home, cwd);
        recordLeaves(tree, "", ConfigSource.DEFAULT);

        var user = readDir(options.userDir(), ConfigSource.Kind.USER);
        var project = readDir(options.projectDir(), ConfigSource.Kind.PROJECT);
        var explicit = options.configFile() == null ? null
                : readFile(options.configFile(), ConfigSource.Kind.CONFIG_FILE);

        var trustList = new ArrayList<String>();
        for (var layer : new Layer[] {user, explicit}) {
            if (layer != null && layer.tree.get("trust") instanceof Map<?, ?> t
                    && t.get("projects") instanceof List<?> l) {
                l.forEach(p -> trustList.add(normalize(Path.of(String.valueOf(p)))));
            }
        }
        var trusted = options.trusted() || trustList.contains(normalize(cwd))
                || trustList.contains(normalize(TrustStore.mainProject(cwd)))
                || new TrustStore(options.home()).isTrusted(cwd);

        if (project != null) {
            if (project.tree.get("trust") instanceof Map<?, ?> t && t.containsKey("projects")) {
                @SuppressWarnings("unchecked")
                var tm = (Map<String, Object>) t;
                tm.remove("projects");
                warn("Ignored key trust.projects in " + project.source.describe()
                        + ": only the user config can trust a project.");
                if (tm.isEmpty()) {
                    project.tree.remove("trust");
                }
            }
            if (!trusted) {
                RestrictedMode.restrict(project.tree, project.source.describe()).forEach(this::warn);
            }
        }

        for (var layer : new Layer[] {user, project, explicit}) {
            if (layer != null) {
                merge(tree, layer.tree, "", layer.source);
            }
        }

        var env = envLayer();
        validateKeys(env, "", "environment");
        merge(tree, env, "", ConfigSource.of(ConfigSource.Kind.ENV));

        var flags = flagLayer();
        merge(tree, flags, "", ConfigSource.of(ConfigSource.Kind.FLAG));

        registerSecrets(tree);
        normalizeWindows(tree);
        JeorjeConfig config;
        try {
            config = bind(tree);
        } catch (ConfigException e) {
            throw new ConfigException(scrubber.scrub(e.getMessage()));
        }
        validate(config);
        return new LoadedConfig(config, tree, sources, warnings, trusted);
    }

    // ---------------------------------------------------------------- reading

    private record Layer(Map<String, Object> tree, ConfigSource source) {
    }

    private Layer readDir(Path dir, ConfigSource.Kind kind) {
        Path chosen = null;
        var ignored = new ArrayList<String>();
        for (var name : FILE_NAMES) {
            var candidate = dir.resolve(name);
            if (Files.isRegularFile(candidate)) {
                if (chosen == null) {
                    chosen = candidate;
                } else {
                    ignored.add(candidate.toString());
                }
            }
        }
        if (chosen == null) {
            return null;
        }
        if (!ignored.isEmpty()) {
            warn("More than one config file in " + dir + ". Using " + chosen + ". Ignored: "
                    + String.join(", ", ignored) + ".");
        }
        return readFile(chosen, kind);
    }

    @SuppressWarnings("unchecked")
    private Layer readFile(Path file, ConfigSource.Kind kind) {
        var name = file.getFileName().toString().toLowerCase();
        ObjectMapper mapper;
        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            mapper = YAML;
        } else if (name.endsWith(".json")) {
            mapper = JSON;
        } else {
            throw new ConfigException("Config file has an unknown extension (use .yaml, .yml, or .json): " + file);
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new ConfigException("Cannot read config file " + file + ": " + e.getMessage(), e);
        }
        Object root;
        try {
            root = text.isBlank() ? null : mapper.readValue(text, Object.class);
        } catch (JacksonException e) {
            throw new ConfigException("Cannot parse config file " + file + ": " + e.getOriginalMessage());
        }
        if (root != null && !(root instanceof Map)) {
            throw new ConfigException("Config file must hold a mapping at the top level: " + file);
        }
        var tree = root == null ? new LinkedHashMap<String, Object>() : (Map<String, Object>) root;
        var source = new ConfigSource(kind, file);
        validateKeys(tree, "", file.toString());
        expandPaths(tree, options.home(), options.cwd());
        resolvePromptFiles(tree, file.toAbsolutePath().getParent());
        return new Layer(tree, source);
    }

    private Map<String, Object> envLayer() {
        var tree = new LinkedHashMap<String, Object>();
        var token = options.env().get("JEORJE_SWARM_TOKEN");
        if (token != null && !token.isBlank()) {
            setPath(tree, "swarm.token", token);
        }
        return tree;
    }

    private Map<String, Object> flagLayer() {
        var tree = new LinkedHashMap<String, Object>();
        options.flags().forEach((key, value) -> setPath(tree, key, value));
        validateKeys(tree, "", "flags");
        expandPaths(tree, options.home(), options.cwd());
        return tree;
    }

    private static void setPath(Map<String, Object> tree, String dotted, Object value) {
        var parts = dotted.split("\\.");
        var node = tree;
        for (var i = 0; i < parts.length - 1; i++) {
            @SuppressWarnings("unchecked")
            var child = (Map<String, Object>) node.computeIfAbsent(parts[i], k -> new LinkedHashMap<String, Object>());
            node = child;
        }
        node.put(parts[parts.length - 1], value);
    }

    private static String normalize(Path path) {
        try {
            return path.toRealPath().toString();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    // ---------------------------------------------------------------- paths

    @SuppressWarnings("unchecked")
    static void expandPaths(Map<String, Object> tree, Path home, Path cwd) {
        if (tree.get("mcp") instanceof Map<?, ?> mcp && mcp.get("servers") instanceof Map<?, ?> servers) {
            for (var server : servers.values()) {
                if (server instanceof Map<?, ?> m) {
                    for (var key : List.of("cwd", "command")) {
                        if (m.get(key) instanceof String s) {
                            ((Map<String, Object>) m).put(key, PathExpander.expand(s, home, cwd));
                        }
                    }
                }
            }
        }
        for (var key : ConfigDefaults.PATH_KEYS) {
            var parts = key.split("\\.");
            Object node = tree;
            for (var i = 0; i < parts.length - 1 && node instanceof Map; i++) {
                node = ((Map<String, Object>) node).get(parts[i]);
            }
            if (node instanceof Map<?, ?> parent) {
                var map = (Map<String, Object>) parent;
                var last = parts[parts.length - 1];
                var value = map.get(last);
                if (value instanceof String s) {
                    map.put(last, PathExpander.expand(s, home, cwd));
                } else if (value instanceof List<?> l) {
                    var out = new ArrayList<Object>();
                    for (var item : l) {
                        out.add(item instanceof String s ? PathExpander.expand(s, home, cwd) : item);
                    }
                    map.put(last, out);
                }
            }
        }
    }

    /** A relative prompt file starts at the directory of the config file that names it. */
    @SuppressWarnings("unchecked")
    private void resolvePromptFiles(Map<String, Object> tree, Path base) {
        if (tree.get("prompts") instanceof Map<?, ?> prompts) {
            for (var value : prompts.values()) {
                if (value instanceof Map<?, ?> prompt && prompt.get("file") instanceof String f && !f.isBlank()
                        && !Path.of(f).isAbsolute()) {
                    ((Map<String, Object>) prompt).put("file", base.resolve(f).normalize().toString());
                }
            }
        }
    }

    // ---------------------------------------------------------------- validation

    /** Throws for a key that the config does not define. The message holds the key path. */
    @SuppressWarnings("unchecked")
    private void validateKeys(Map<String, Object> layer, String prefix, String origin) {
        var schema = prefix.isEmpty() ? ConfigDefaults.tree(options.home(), options.cwd()) : null;
        checkNode(layer, schema, prefix, origin);
    }

    @SuppressWarnings("unchecked")
    private void checkNode(Map<String, Object> node, Map<String, Object> schema, String prefix, String origin) {
        for (var entry : node.entrySet()) {
            var path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (!schema.containsKey(entry.getKey())) {
                throw new ConfigException("Unknown config key: " + path + " (in " + origin + ")");
            }
            var expected = schema.get(entry.getKey());
            var value = entry.getValue();
            if (expected instanceof Map<?, ?> em && !ConfigDefaults.FREE_FORM.contains(path)) {
                if (value == null) {
                    continue;
                }
                if (!(value instanceof Map<?, ?> vm)) {
                    throw new ConfigException("Config key " + path + " must be a mapping (in " + origin + ")");
                }
                checkNode((Map<String, Object>) vm, (Map<String, Object>) em, path, origin);
            } else if (path.equals("permissions.rules") && value instanceof List<?> rules) {
                for (var i = 0; i < rules.size(); i++) {
                    if (!(rules.get(i) instanceof Map<?, ?> rule)) {
                        throw new ConfigException("Config key " + path + "[" + i + "] must be a mapping (in " + origin + ")");
                    }
                    for (var rk : rule.keySet()) {
                        if (!ConfigDefaults.RULE_KEYS.contains(String.valueOf(rk))) {
                            throw new ConfigException("Unknown config key: " + path + "[" + i + "]." + rk
                                    + " (in " + origin + ")");
                        }
                    }
                }
            }
        }
    }

    /** Checks context.window and context.windows, and turns their values into integers. */
    @SuppressWarnings("unchecked")
    private static void normalizeWindows(Map<String, Object> tree) {
        if (!(tree.get("context") instanceof Map<?, ?> raw)) {
            return;
        }
        var context = (Map<String, Object>) raw;
        if (context.containsKey("window")) {
            context.put("window", tokens(context.get("window"), "context.window"));
        }
        var windows = context.get("windows");
        if (windows == null) {
            return;
        }
        if (!(windows instanceof Map<?, ?> map)) {
            throw new ConfigException("Invalid config value: context.windows must be a mapping from model to tokens");
        }
        for (var entry : ((Map<String, Object>) map).entrySet()) {
            entry.setValue(tokens(entry.getValue(), "context.windows." + entry.getKey()));
        }
    }

    private static Integer tokens(Object value, String key) {
        long number;
        switch (value) {
            case Integer i -> number = i;
            case Long l -> number = l;
            case Short s -> number = s;
            case Byte b -> number = b;
            case String s -> {
                try {
                    number = Long.parseLong(s.strip().replace("_", ""));
                } catch (NumberFormatException e) {
                    throw new ConfigException("Invalid config value: " + key + " must be a positive integer");
                }
            }
            case null, default -> throw new ConfigException("Invalid config value: " + key
                    + " must be a positive integer");
        }
        if (number <= 0 || number > Integer.MAX_VALUE) {
            throw new ConfigException("Invalid config value: " + key + " must be a positive integer");
        }
        return (int) number;
    }

    private void validate(JeorjeConfig c) {
        require(c.llm().provider().equals("openai-compatible"), "llm.provider", "must be openai-compatible");
        for (var key : c.llm().extraBody().keySet()) {
            require(!JeorjeConfig.RESERVED_BODY_KEYS.contains(key), "llm.extraBody",
                    "cannot set the key '" + key + "', because Jeorje sets it");
        }
        require(percent(c.context().budgetPercent()), "context.budgetPercent", "must be 0 to 100");
        require(c.context().recentMaxPercent() >= 1 && c.context().recentMaxPercent() <= 100,
                "context.recentMaxPercent", "must be 1 to 100");
        require(percent(c.context().compaction().softPercent()), "context.compaction.softPercent", "must be 0 to 100");
        require(percent(c.context().compaction().hardPercent()), "context.compaction.hardPercent", "must be 0 to 100");
        require(c.context().compaction().softPercent() <= c.context().compaction().hardPercent(),
                "context.compaction.softPercent", "must not be above hardPercent");
        require(c.index().userBoost() > 0, "index.userBoost", "must be above 0");
        require(c.plan().maxExploreCalls() >= 5 && c.plan().maxExploreCalls() <= 500, "plan.maxExploreCalls",
                "must be 5 to 500");
        require(c.tools().patch().fuzz() >= 0 && c.tools().patch().fuzz() <= 3, "tools.patch.fuzz", "must be 0 to 3");
        require(Set.of("fail", "markers").contains(c.tools().patch().conflicts()), "tools.patch.conflicts",
                "must be fail or markers");
        require(Set.of("myers", "myers-linear").contains(c.tools().diff().algorithm()), "tools.diff.algorithm",
                "must be myers or myers-linear");
        require(Set.of("auto", "always", "never").contains(c.worktree().isolation()), "worktree.isolation",
                "must be auto, always, or never");
        require(Set.of("head", "snapshot").contains(c.worktree().base()), "worktree.base", "must be head or snapshot");
        require(Set.of("delete-if-clean", "keep", "remove").contains(c.worktree().onFinish()), "worktree.onFinish",
                "must be delete-if-clean, keep, or remove");
        require(Set.of("merge", "squash", "rebase").contains(c.worktree().merge()), "worktree.merge",
                "must be merge, squash, or rebase");
        require(Set.of("auto", "manual").contains(c.worktree().mergeBack()), "worktree.mergeBack",
                "must be auto or manual");
        require(Set.of("advisor", "none").contains(c.implement().review()), "implement.review",
                "must be advisor or none");
        require(Set.of("auto", "on", "off").contains(c.ui().keyboardProtocol()), "ui.keyboardProtocol",
                "must be auto, on, or off");
        require(Set.of("quiet", "normal", "verbose").contains(c.ui().chat().verbosity()), "ui.chat.verbosity",
                "must be quiet, normal, or verbose");
        require(c.agents().stepLimit() > 0, "agents.stepLimit", "must be a positive integer");
        require(c.subagents().stepLimit() > 0, "subagents.stepLimit", "must be a positive integer");
        for (var entry : c.mcp().servers().entrySet()) {
            validateMcp(entry.getKey(), entry.getValue());
        }
        for (var rule : c.permissions().rules()) {
            require(rule.tool() != null && !rule.tool().isBlank(), "permissions.rules.tool", "is required");
            require(rule.decision() != null && Set.of("allow", "ask", "deny").contains(rule.decision()),
                    "permissions.rules.decision", "must be allow, ask, or deny");
        }
        for (var entry : Map.of("prompts.system", c.prompts().system(), "prompts.compaction", c.prompts().compaction()).entrySet()) {
            var p = entry.getValue();
            require(ENUM_MODES.contains(p.mode()), entry.getKey() + ".mode", "must be append or replace");
            require(p.text().isEmpty() || p.file().isEmpty(), entry.getKey(), "must set text or file, not both");
        }
    }

    private static void validateMcp(String name, JeorjeConfig.McpServer s) {
        var key = "mcp.servers." + name;
        require(name.matches("[a-z0-9_-]+"), key, "has a bad name (use a-z, 0-9, _ and -)");
        require(Set.of("stdio", "http").contains(s.transport()), key + ".transport", "must be stdio or http");
        switch (s.transport()) {
            case "stdio" -> require(s.command() != null && !s.command().isBlank(), key + ".command", "is required");
            default -> require(s.url() != null && !s.url().isBlank(), key + ".url", "is required");
        }
        require(s.timeoutSeconds() > 0, key + ".timeoutSeconds", "must be a positive integer");
    }

    private static boolean percent(int v) {
        return v >= 0 && v <= 100;
    }

    private static void require(boolean ok, String key, String message) {
        if (!ok) {
            throw new ConfigException("Invalid config value: " + key + " " + message);
        }
    }

    // ---------------------------------------------------------------- merge

    @SuppressWarnings("unchecked")
    private void merge(Map<String, Object> base, Map<String, Object> overlay, String prefix, ConfigSource src) {
        for (var entry : overlay.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            var path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value == null) {
                continue;
            }
            if (ConfigDefaults.ATOMIC.contains(path) && value instanceof Map) {
                var fresh = (Map<String, Object>) navigateDefaults(path);
                sources.keySet().removeIf(k -> k.startsWith(path + "."));
                base.put(key, fresh);
                recordLeaves(fresh, path, ConfigSource.DEFAULT);
            }
            var existing = base.get(key);
            if (value instanceof Map<?, ?> vm && existing instanceof Map<?, ?> em) {
                merge((Map<String, Object>) em, (Map<String, Object>) vm, path, src);
            } else if (value instanceof Map<?, ?> vm) {
                var copy = new LinkedHashMap<String, Object>();
                base.put(key, copy);
                merge(copy, (Map<String, Object>) vm, path, src);
            } else if (path.equals("permissions.rules") && value instanceof List<?> add
                    && existing instanceof List<?> old) {
                var all = new ArrayList<Object>(old);
                all.addAll(add);
                base.put(key, all);
                sources.put(path, src);
            } else {
                base.put(key, value instanceof List<?> l ? new ArrayList<Object>(l) : value);
                sources.put(path, src);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Object navigateDefaults(String path) {
        Object node = ConfigDefaults.tree(options.home(), options.cwd());
        for (var part : path.split("\\.")) {
            node = ((Map<String, Object>) node).get(part);
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private void recordLeaves(Object value, String path, ConfigSource src) {
        if (value instanceof Map<?, ?> m && !m.isEmpty()) {
            m.forEach((k, v) -> recordLeaves(v, path.isEmpty() ? String.valueOf(k) : path + "." + k, src));
        } else if (!path.isEmpty()) {
            sources.put(path, src);
        }
    }

    // ---------------------------------------------------------------- logging

    private void registerSecrets(Map<String, Object> tree) {
        scrubber.register(options.env().get("JEORJE_SWARM_TOKEN"));
        if (tree.get("swarm") instanceof Map<?, ?> s && s.get("token") instanceof String t) {
            scrubber.register(t);
        }
    }

    private void warn(String message) {
        var clean = scrubber.scrub(message);
        warnings.add(clean);
        log.atWarn().log(() -> clean);
    }
}
