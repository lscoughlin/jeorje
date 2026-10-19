package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.StubEmbedder;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** The keys {@code agents.stepLimit} and {@code subagents.stepLimit} reach the real runtime. */
class StepLimitWiringTest {

    private static CliEnvironment env(TempWorkspace home, TempWorkspace cwd) {
        return new CliEnvironment(home.root(), cwd.root(), Map.of("OPENROUTER_API_KEY", "test-key"),
                System.out, System.err, (config, model, key) -> new ScriptedLlmClient(),
                config -> Optional.of(new StubEmbedder()), false);
    }

    private static GlobalOptions options(TempWorkspace cwd, String... more) {
        var args = new java.util.ArrayList<>(java.util.List.of("--dir", cwd.root().toString()));
        args.addAll(java.util.List.of(more));
        return CommandLine.populateCommand(new ContextWindowsTest.Holder(), args.toArray(String[]::new)).global;
    }

    @Test
    void theDefaultsAreTwoHundred() throws Exception {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var runtime = runtime(home, cwd);
            try (runtime) {
                assertEquals(200, runtime.agent().settings().stepLimit());
            }
        }
    }

    @Test
    void theConfigKeysSetTheStepLimitsOfTheAgentAndTheSubagents() throws Exception {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            // The subagent runtime exists only with the swarm on, so the test turns the swarm on with a free port.
            cwd.write(".jeorje/config.yaml", "agents:\n  stepLimit: 7\nsubagents:\n  stepLimit: 9\n"
                    + "swarm:\n  enabled: true\n  bind: \"127.0.0.1:0\"\n");
            var runtime = runtime(home, cwd);
            try (runtime) {
                assertEquals(7, runtime.agent().settings().stepLimit());
                assertEquals(9, runtime.subagents().limits().maxModelCalls());
            }
        }
    }

    private static Wiring.Runtime runtime(TempWorkspace home, TempWorkspace cwd) {
        var env = env(home, cwd);
        var options = options(cwd, "--trust");
        return Wiring.open(Wiring.loadConfig(options, env), options, env);
    }
}
