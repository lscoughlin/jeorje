package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.junit.jupiter.api.Test;

/** The run mode starts the swarm from the config and from the flags. */
class SwarmWiringTest {

    private static boolean hasPeerSpawn(ScriptedLlmClient llm) {
        return llm.requests().getFirst().tools().stream()
                .filter(t -> t.name().equals("spawn_agent"))
                .anyMatch(t -> t.inputSchema().path("properties").has("peer")
                        && t.inputSchema().path("properties").has("capability"));
    }

    @Test
    void theSwarmIsOffByDefault() {
        var llm = new ScriptedLlmClient(Response.text("ok"));
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), llm);
            assertEquals(0, cli.run("run", "hi"), cli.err());
            assertFalse(llm.requests().getFirst().tools().stream().anyMatch(t -> t.name().equals("spawn_agent")));
        }
    }

    @Test
    void swarmEnabledInTheConfigStartsTheSwarmAndOffersPeerSpawn() {
        var llm = new ScriptedLlmClient(Response.text("ok"));
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var file = cwd.write("swarm.yaml", "swarm:\n  enabled: true\n  bind: \"127.0.0.1:0\"\n");
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), llm);
            assertEquals(0, cli.run("--config", file.toString(), "run", "hi"), cli.err());
            assertTrue(hasPeerSpawn(llm), llm.requests().getFirst().tools().toString());
        }
    }

    @Test
    void thePeerFlagTurnsTheSwarmOnAndTheTokenComesFromTheEnvironment() {
        var llm = new ScriptedLlmClient(Response.text("ok"));
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var file = cwd.write("bind.yaml", "swarm:\n  bind: \"127.0.0.1:0\"\n");
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of("JEORJE_SWARM_TOKEN", "t0ken-value-123"), llm);
            assertEquals(0, cli.run("--config", file.toString(), "--peer", "127.0.0.1:9", "run", "hi"), cli.err());
            assertTrue(hasPeerSpawn(llm));
            assertFalse(cli.err().contains("t0ken-value-123"));
            assertFalse(cli.out().contains("t0ken-value-123"));
        }
    }
}
