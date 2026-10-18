package org.github.lscoughlin.jeorje.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.Callable;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.agent.SystemPromptBuilder;
import org.github.lscoughlin.jeorje.core.compaction.CompactorPrompt;
import org.github.lscoughlin.jeorje.core.compaction.ModelRoles;
import org.github.lscoughlin.jeorje.core.compaction.Protection;
import org.github.lscoughlin.jeorje.core.config.ConfigLoader;
import org.github.lscoughlin.jeorje.core.config.ConfigOptions;
import org.github.lscoughlin.jeorje.core.config.LoadedConfig;
import org.github.lscoughlin.jeorje.core.state.AgentState;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code jeorje config}: prints the effective config, the prompts, or the key bindings, or writes a new config. */
@Command(name = "config", mixinStandardHelpOptions = true,
        description = "Print the effective config with the source of each value.")
public final class ConfigCommand implements Callable<Integer> {

    @Option(names = "--format", description = "Output format: yaml (default) or json.")
    String format = "yaml";

    @Option(names = "--init", description = "Write a new config.yaml with the defaults into .jeorje of the project.")
    boolean init;

    @Option(names = "--show-prompts", description = "Print the assembled system prompt and compaction prompt.")
    boolean showPrompts;

    @Option(names = "--keys", description = "Print the effective key bindings.")
    boolean keys;

    private final CliEnvironment env;
    private final GlobalOptions options;

    ConfigCommand(CliEnvironment env, GlobalOptions options) {
        this.env = env;
        this.options = options;
    }

    @Override
    public Integer call() throws IOException {
        if (!format.equals("yaml") && !format.equals("json")) {
            throw new CliException("Unknown format '" + format + "'. Use yaml or json.");
        }
        if (init) return writeNewConfig();
        var loaded = Wiring.loadConfig(options, env);
        Wiring.configureLogging(loaded.config(), env, true);
        Wiring.warnUnattended(options, env);
        loaded.warnings().forEach(w -> env.err().println("Warning: " + w));
        if (showPrompts) {
            printPrompts(loaded);
        } else if (keys) {
            KeyBindings.lines(loaded).forEach(env.out()::println);
        } else {
            env.out().print(format.equals("json") ? ConfigPrinter.json(loaded) + "\n" : ConfigPrinter.yaml(loaded));
        }
        return 0;
    }

    private Integer writeNewConfig() throws IOException {
        var dir = Wiring.workDir(options, env).resolve(".jeorje");
        var target = dir.resolve("config.yaml");
        if (Files.exists(target)) throw new CliException("The file already exists: " + target);
        // The defaults only: no user config, no project config, no environment.
        var scratch = Path.of(System.getProperty("java.io.tmpdir")).resolve("jeorje-no-config-" + System.nanoTime());
        var defaults = ConfigLoader.load(new ConfigOptions(env.home(), scratch, Map.of("JEORJE_HOME", scratch.toString()),
                Map.of(), null, false));
        Files.createDirectories(dir);
        Files.writeString(target, defaults.toYaml());
        env.out().println("Wrote " + target);
        return 0;
    }

    private void printPrompts(LoadedConfig loaded) {
        var config = loaded.config();
        var cwd = Wiring.workDir(options, env);
        var policy = Wiring.policy(config, options, env, cwd, "prompts", new org.github.lscoughlin.jeorje.core.config.SecretScrubber());
        var registry = Wiring.registry(config, policy, policy.network(), new AgentState(), "prompts");
        var system = SystemPromptBuilder.build(new SystemPromptBuilder.Parts(config.prompts().system(),
                registry.definitions(), cwd, System.getProperty("os.name", "unknown"), LocalDate.now(),
                Wiring.instructions(config, cwd), null));
        env.out().println("# System prompt (" + ModelRoles.from(config.models()).defaultModel() + ")");
        env.out().println(system);
        env.out().println();
        env.out().println("# Compaction prompt (level 1, target 50%, class AGGRESSIVE)");
        env.out().println(CompactorPrompt.build(config.prompts().compaction(), cwd, 1, 0.5, Protection.AGGRESSIVE,
                config.context().compaction().maxKeywords()));
    }
}
