package org.github.lscoughlin.jeorje.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The immutable effective config. The tree follows the keys of the design. The loader fills every
 * value, so the defaults come from {@link ConfigDefaults}. A config holds the name of the API key
 * variable, never the key. The swarm token is hidden in {@code toString}.
 */
public record JeorjeConfig(Llm llm, Models models, Context context, Index index, Permissions permissions,
                           Swarm swarm, Tools tools, Instructions instructions, Skills skills, Trust trust,
                           Agents agents, Plan plan, Subagents subagents, Worktree worktree,
                           Implement implement, Prompts prompts, Ui ui, Editor editor, Logging logging) {

    /** The built-in defaults. */
    public static JeorjeConfig defaults() {
        return ConfigLoader.bind(ConfigDefaults.tree(java.nio.file.Path.of(System.getProperty("user.home")),
                java.nio.file.Path.of(System.getProperty("user.dir"))));
    }

    static <T> List<T> list(List<T> in) {
        return in == null ? List.of() : List.copyOf(in);
    }

    static Map<String, Object> freezeMap(Map<String, Object> in) {
        if (in == null) {
            return Map.of();
        }
        var out = new LinkedHashMap<String, Object>();
        in.forEach((k, v) -> out.put(k, freeze(v)));
        return Collections.unmodifiableMap(out);
    }

    @SuppressWarnings("unchecked")
    static Object freeze(Object value) {
        return switch (value) {
            case Map<?, ?> m -> freezeMap((Map<String, Object>) m);
            case List<?> l -> {
                var out = new ArrayList<Object>();
                l.forEach(x -> out.add(freeze(x)));
                yield Collections.unmodifiableList(out);
            }
            case null, default -> value;
        };
    }

    public record Llm(String provider, String baseUrl, List<String> apiKeyEnv, int maxOutputTokens,
                      Map<String, String> headers) {
        public Llm {
            apiKeyEnv = list(apiKeyEnv);
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    public record Models(@JsonProperty("default") String defaultModel, String compactor, String advisor) {
    }

    public record Compaction(int softPercent, int hardPercent, List<Double> levels, int batchChunks,
                             int batchTokens, int maxKeywords, int protectedRecentTurns,
                             int aggressiveDropAfterTurns) {
        public Compaction {
            levels = list(levels);
        }
    }

    /**
     * The context settings.
     *
     * @param window  the context window in tokens, for all models
     * @param windows the context window in tokens for single models, by model slug. It wins over window.
     */
    public record Context(int window, Map<String, Integer> windows, int budgetPercent, int recentTurns,
                          Compaction compaction) {
        public Context {
            windows = windows == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(windows));
        }

        /** The window for a model: the entry in windows, else window. */
        public int windowFor(String model) {
            var override = model == null ? null : windows.get(model);
            return override != null && override > 0 ? override : window;
        }
    }

    public record Index(boolean embeddings, String modelCache) {
    }

    public record PermissionRule(String tool, String pattern, String decision) {
    }

    public record Webfetch(List<String> allow, List<String> deny) {
        public Webfetch {
            allow = list(allow);
            deny = list(deny);
        }
    }

    public record Permissions(List<PermissionRule> rules, List<String> extraDirectories, Webfetch webfetch) {
        public Permissions {
            rules = list(rules);
            extraDirectories = list(extraDirectories);
        }
    }

    public record Swarm(boolean enabled, String bind, List<String> wka, String token, long gossipIntervalMs) {
        public Swarm {
            wka = list(wka);
        }

        @Override
        public String toString() {
            return "Swarm[enabled=" + enabled + ", bind=" + bind + ", wka=" + wka + ", token="
                    + (token == null || token.isEmpty() ? "" : "***") + ", gossipIntervalMs=" + gossipIntervalMs + "]";
        }
    }

    public record Read(int frontMatterScanLines, int frontMatterMaxLines, List<String> directoryDocs,
                       int directoryDocChars, int directoryDocFallbackLines) {
        public Read {
            directoryDocs = list(directoryDocs);
        }
    }

    public record Diff(int context, int maxLines, String algorithm) {
    }

    public record Patch(int fuzz, String conflicts) {
    }

    public record Bash(String shell, int timeoutSeconds) {
    }

    public record Tools(Read read, Diff diff, Patch patch, Bash bash, List<Map<String, Object>> extensions) {
        public Tools {
            extensions = extensions == null ? List.of()
                    : extensions.stream().map(JeorjeConfig::freezeMap).toList();
        }
    }

    public record Instructions(boolean enabled, int maxBytes, int maxTotalBytes, List<String> userFiles) {
        public Instructions {
            userFiles = list(userFiles);
        }
    }

    public record Skills(boolean enabled, int maxListChars, int maxProtectedTokens, boolean honorAllowedTools,
                         List<String> dirs, List<String> compat) {
        public Skills {
            dirs = list(dirs);
            compat = list(compat);
        }
    }

    public record Trust(List<String> projects) {
        public Trust {
            projects = list(projects);
        }
    }

    public record Agents(List<String> dirs) {
        public Agents {
            dirs = list(dirs);
        }
    }

    public record Plan(String dir) {
    }

    public record Subagents(int askTimeoutSeconds, int maxAsks) {
    }

    public record Sync(int maxRounds, int maxModelCalls, String verify) {
    }

    public record Worktree(String isolation, String base, String branchPrefix, List<String> copy,
                           List<String> link, String onFinish, String merge, String mergeBack,
                           int mergeWaitSeconds, Sync sync) {
        public Worktree {
            copy = list(copy);
            link = list(link);
        }
    }

    public record Implement(int parallel, String model, String review, int maxAttempts, int maxModelCalls,
                            boolean handoff) {
    }

    /** One prompt. Use {@code text} or {@code file}. An empty string means not set. */
    public record Prompt(String mode, String text, String file) {
    }

    public record Prompts(Prompt system, Prompt compaction) {
    }

    public record Highlight(boolean enabled, int maxLines, boolean diff, boolean read, boolean fences,
                            Map<String, String> aliases) {
        public Highlight {
            aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
        }
    }

    public record Chat(Highlight highlight) {
    }

    public record Mouse(boolean enabled, int wheelLines, boolean copyOnSelect) {
    }

    public record Ui(String keyboardProtocol, Chat chat, Mouse mouse, Map<String, Object> keys) {
        public Ui {
            keys = freezeMap(keys);
        }
    }

    public record EditorSyntax(boolean enabled, long maxBytes, List<String> dirs, boolean systemDirs,
                               boolean embedded, boolean injections, int replayLines, int maxLineChars,
                               int budgetMs) {
        public EditorSyntax {
            dirs = list(dirs);
        }
    }

    public record Editor(EditorSyntax syntax, Map<String, Object> keys) {
        public Editor {
            keys = freezeMap(keys);
        }
    }

    public record Logging(String level, String file) {
    }
}
