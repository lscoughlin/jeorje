package org.github.lscoughlin.jeorje.core.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** The built-in defaults and the shape rules of the config tree. */
final class ConfigDefaults {

    static final String YAML = """
            llm:
              provider: openai-compatible
              baseUrl: https://openrouter.ai/api/v1
              apiKeyEnv: [OPENROUTER_API_KEY]
              maxOutputTokens: 8192
              headers: {}
            models:
              default: ~deepseek/deepseek-flash-latest
              compactor: ""
              advisor: ""
            context:
              window: 128000
              windows: {}
              budgetPercent: 60
              recentTurns: 6
              compaction:
                softPercent: 50
                hardPercent: 80
                levels: [1.0, 0.5, 0.2, 0.0]
                batchChunks: 20
                batchTokens: 16000
                maxKeywords: 12
                protectedRecentTurns: 6
                aggressiveDropAfterTurns: 12
            index:
              embeddings: true
              modelCache: ${HOME}/.cache/jeorje/models
            permissions:
              rules: []
              extraDirectories: []
              webfetch:
                allow: []
                deny: []
            swarm:
              enabled: false
              bind: 127.0.0.1:7600
              wka: []
              token: ""
              gossipIntervalMs: 1000
              discovery: false
              tls:
                keyStore: ""
                passwordEnv: ""
            tools:
              read:
                frontMatterScanLines: 25
                frontMatterMaxLines: 40
                directoryDocs: [AGENTS.md, README.md, llms.txt, llm.txt]
                directoryDocChars: 2000
                directoryDocFallbackLines: 20
              diff:
                context: 3
                maxLines: 50000
                algorithm: myers
              patch:
                fuzz: 2
                conflicts: fail
              bash:
                shell: /bin/sh
                timeoutSeconds: 120
              extensions: []
            instructions:
              enabled: true
              maxBytes: 32768
              maxTotalBytes: 65536
              userFiles: ["${HOME}/.jeorje/AGENTS.md", "${HOME}/.agents/AGENTS.md"]
            skills:
              enabled: true
              maxListChars: 8000
              maxProtectedTokens: 20000
              honorAllowedTools: false
              dirs: []
              compat: []
            trust:
              projects: []
            agents:
              dirs: []
            plan:
              dir: plan
            subagents:
              askTimeoutSeconds: 120
              maxAsks: 10
            worktree:
              isolation: auto
              base: head
              branchPrefix: jeorje
              copy: []
              link: []
              onFinish: delete-if-clean
              merge: merge
              mergeBack: auto
              mergeWaitSeconds: 300
              sync:
                maxRounds: 3
                maxModelCalls: 30
                verify: ""
            implement:
              parallel: 3
              model: ""
              review: advisor
              maxAttempts: 2
              maxModelCalls: 200
              handoff: true
            prompts:
              system:
                mode: append
                text: ""
                file: ""
              compaction:
                mode: replace
                text: ""
                file: ""
            ui:
              keyboardProtocol: auto
              chat:
                highlight:
                  enabled: true
                  maxLines: 2000
                  diff: true
                  read: true
                  fences: true
                  aliases: {}
              mouse:
                enabled: true
                wheelLines: 3
                copyOnSelect: false
              keys: {}
            editor:
              syntax:
                enabled: true
                maxBytes: 5242880
                dirs: []
                systemDirs: true
                embedded: true
                injections: true
                replayLines: 2000
                maxLineChars: 2000
                budgetMs: 200
              keys: {}
            logging:
              level: INFO
              file: ${HOME}/.jeorje/logs/jeorje.log
            """;

    /** Maps with user-chosen keys. The unknown-key check stops here. */
    static final Set<String> FREE_FORM = Set.of("llm.headers", "ui.chat.highlight.aliases", "ui.keys",
            "editor.keys", "context.windows");

    /** Maps that a layer replaces as one unit. Unset fields come back to the defaults. */
    static final Set<String> ATOMIC = Set.of("prompts.system", "prompts.compaction");

    /** Keys with path values. The loader expands them. */
    static final List<String> PATH_KEYS = List.of("index.modelCache", "logging.file", "editor.syntax.dirs",
            "skills.dirs", "skills.compat", "agents.dirs", "instructions.userFiles", "permissions.extraDirectories",
            "prompts.system.file", "prompts.compaction.file", "plan.dir", "trust.projects");

    /** Keys that hold secrets. Output hides them. */
    static final Set<String> SECRET_KEYS = Set.of("swarm.token");

    static final Set<String> RULE_KEYS = Set.of("tool", "pattern", "decision");

    private ConfigDefaults() {
    }

    /** A fresh, mutable default tree with the path values expanded. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> tree(Path home, Path cwd) {
        var map = (Map<String, Object>) YAMLMapper.builder().build().readValue(YAML, Map.class);
        ConfigLoader.expandPaths(map, home, cwd);
        return map;
    }
}
