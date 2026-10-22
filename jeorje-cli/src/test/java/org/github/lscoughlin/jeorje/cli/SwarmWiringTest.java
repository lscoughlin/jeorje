package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void theSwarmStaysOffWhenTheConfigTurnsItOff() {
        var llm = new ScriptedLlmClient(Response.text("ok"));
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var file = cwd.write("off.yaml", "swarm:\n  enabled: false\n");
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), llm);
            assertEquals(0, cli.run("--config", file.toString(), "run", "hi"), cli.err());
            assertFalse(llm.requests().getFirst().tools().stream().anyMatch(t -> t.name().equals("spawn_agent")));
        }
    }

    @Test
    void theDefaultConfigStartsTheSwarm() {
        var llm = new ScriptedLlmClient(Response.text("ok"));
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), llm);
            assertEquals(0, cli.run("run", "hi"), cli.err());
            assertTrue(hasPeerSpawn(llm), llm.requests().getFirst().tools().toString());
        }
    }

    @Test
    void theNoSwarmFlagTurnsTheSwarmOff() {
        var llm = new ScriptedLlmClient(Response.text("ok"));
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), llm);
            assertEquals(0, cli.run("--no-swarm", "run", "hi"), cli.err());
            assertFalse(llm.requests().getFirst().tools().stream().anyMatch(t -> t.name().equals("spawn_agent")));
        }
    }

    @Test
    void swarmFlagsSetTheConfigKeys() {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), null);
            var off = Wiring.loadConfig(GlobalOptionsHolder.parse("--no-swarm"), cli.env);
            assertFalse(off.config().swarm().enabled());
            var on = Wiring.loadConfig(GlobalOptionsHolder.parse("--swarm", "--swarm-bind", "127.0.0.1:7777"), cli.env);
            assertTrue(on.config().swarm().enabled());
            assertEquals("127.0.0.1:7777", on.config().swarm().bind());
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

    @Test
    void zeroconfDiscoveryStartsAndStopsWithTheBridge() throws Exception {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var file = cwd.write("disc.yaml", "swarm:\n  enabled: true\n  discovery: true\n"
                    + "  bind: \"127.0.0.1:0\"\n");
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), null);
            var loaded = Wiring.loadConfig(new GlobalOptionsHolder(file).options(), cli.env);
            assertTrue(loaded.config().swarm().discovery());
            // Without a multicast network, zeroconf is not available and the swarm runs without it.
            var bridge = SwarmBridge.forConfig(loaded.config(), home.root(), cwd.root(), text -> { }, null).start();
            assertNotNull(bridge.node());
            bridge.close();
        }
    }

    @Test
    void aKeyStoreThatCannotBeLoadedStopsTheRunWithAClearError() {
        var llm = new ScriptedLlmClient(Response.text("ok"));
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var file = cwd.write("tls.yaml", "swarm:\n  enabled: true\n  bind: \"127.0.0.1:0\"\n  tls:\n"
                    + "    keyStore: missing.p12\n");
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), llm);
            assertEquals(2, cli.run("--config", file.toString(), "run", "hi"), cli.err());
            assertTrue(cli.err().contains("TLS key store"), cli.err());
        }
    }

    @Test
    void theTlsPasswordVariableMustBeSet() {
        var tls = new org.github.lscoughlin.jeorje.core.config.JeorjeConfig.Tls("store.p12", "NO_SUCH_VARIABLE");
        var e = org.junit.jupiter.api.Assertions.assertThrows(CliException.class,
                () -> SwarmBridge.tlsContext(tls, java.nio.file.Path.of("."), Map.of()));
        assertTrue(e.getMessage().contains("NO_SUCH_VARIABLE"));
    }

    /** Fills the global options with a config file, as picocli does. */
    private record GlobalOptionsHolder(java.nio.file.Path file) {
        static final class Holder {
            @picocli.CommandLine.Mixin
            GlobalOptions global = new GlobalOptions();
        }

        GlobalOptions options() {
            return parse("--config", file.toString());
        }

        static GlobalOptions parse(String... args) {
            var holder = new Holder();
            new picocli.CommandLine(holder).parseArgs(args);
            return holder.global;
        }
    }
}
