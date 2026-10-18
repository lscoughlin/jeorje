package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.StubEmbedder;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Mixin;

class ContextWindowsTest {

    static final class Holder {
        @Mixin
        GlobalOptions global = new GlobalOptions();
    }

    @Test
    void defaultsToOneHundredTwentyEightThousandTokens() {
        assertEquals(128_000, ContextWindows.resolve(Map.of(), "any/model"));
        assertEquals(128_000, ContextWindows.resolve(Map.of("context", Map.of("budgetPercent", 60)), "any/model"));
    }

    @Test
    void windowSetsAllModelsAndWindowsOverrideSingleModels() {
        var tree = Map.<String, Object>of("context", Map.of("window", 32_000,
                "windows", Map.of("big/model", 1_000_000, "bad/model", -5)));
        assertEquals(32_000, ContextWindows.resolve(tree, "small/model"));
        assertEquals(1_000_000, ContextWindows.resolve(tree, "big/model"));
        assertEquals(32_000, ContextWindows.resolve(tree, "bad/model"), "a bad value is ignored");
        assertEquals(32_000, ContextWindows.resolve(tree, null));
    }

    @Test
    void textValuesAreAccepted() {
        assertEquals(64_000, ContextWindows.resolve(Map.of("context", Map.of("window", "64_000")), "m"));
        assertEquals(128_000, ContextWindows.resolve(Map.of("context", Map.of("window", "lots")), "m"));
    }

    /** The window in the config file changes the budget of the agent that the wiring builds. */
    @Test
    void theWindowChangesTheBudgetOfTheAgent() throws Exception {
        var normal = budgetWith(null, null);
        var small = budgetWith("context:\n  window: 32000\n", null);
        var perModel = budgetWith("context:\n  window: 32000\n  windows:\n    \"%s\": 256000\n", true);
        var otherModel = budgetWith("context:\n  window: 32000\n  windows:\n    \"not/this-model\": 256000\n", null);
        assertEquals(128_000 * 60 / 100, normal);
        assertEquals(32_000 * 60 / 100, small);
        assertEquals(256_000 * 60 / 100, perModel);
        assertEquals(small, otherModel);
    }

    @Test
    void theWindowComesFromTheTypedConfig() throws Exception {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            home.write(".jeorje/config.yaml", "context:\n  window: 50000\n  windows:\n    a/b: 70000\n");
            var env = env(home, cwd);
            var options = CommandLine.populateCommand(new Holder(), "--dir", cwd.root().toString()).global;
            var loaded = Wiring.loadConfig(options, env);
            assertEquals(50_000, Wiring.contextWindow(loaded, "x"));
            assertEquals(70_000, Wiring.contextWindow(loaded, "a/b"));
            assertEquals(70_000, ContextWindows.resolve(loaded.config().context(), "a/b"));
        }
    }

    private static CliEnvironment env(TempWorkspace home, TempWorkspace cwd) {
        return new CliEnvironment(home.root(), cwd.root(), Map.of("OPENROUTER_API_KEY", "test-key"),
                System.out, System.err, (config, model, key) -> new ScriptedLlmClient(),
                config -> Optional.of(new StubEmbedder()), false);
    }

    private static int budgetWith(String yaml, Object perModel) throws Exception {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var env = env(home, cwd);
            var options = CommandLine.populateCommand(new Holder(), "--dir", cwd.root().toString()).global;
            if (yaml != null) {
                var model = Wiring.loadConfig(options, env).config().models().defaultModel();
                home.write(".jeorje/config.yaml", perModel == null ? yaml : yaml.formatted(model));
            }
            var loaded = Wiring.loadConfig(options, env);
            try (var runtime = Wiring.open(loaded, options, env)) {
                return runtime.agent().contextBuilder().budget();
            }
        }
    }
}
