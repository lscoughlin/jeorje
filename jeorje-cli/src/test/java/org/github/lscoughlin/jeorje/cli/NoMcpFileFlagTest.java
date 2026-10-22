package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.github.lscoughlin.jeorje.testkit.StubEmbedder;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** The flag --no-mcp-file stops the read of the .mcp.json file. */
class NoMcpFileFlagTest {

    private static boolean hasServer(String... flags) throws Exception {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            cwd.write(".mcp.json", "{\"mcpServers\": {\"fileserver\": {\"command\": \"tool\"}}}");
            var env = new CliEnvironment(home.root(), cwd.root(), Map.of(), System.out, System.err,
                    (config, model, key) -> null, config -> Optional.of(new StubEmbedder()), false);
            var args = new java.util.ArrayList<>(java.util.List.of("--dir", cwd.root().toString(), "--trust"));
            args.addAll(java.util.List.of(flags));
            var options = CommandLine.populateCommand(new ContextWindowsTest.Holder(), args.toArray(String[]::new)).global;
            return Wiring.loadConfig(options, env).config().mcp().servers().containsKey("fileserver");
        }
    }

    @Test
    void theServerIsPresentWithoutTheFlag() throws Exception {
        assertTrue(hasServer());
    }

    @Test
    void theServerIsAbsentWithTheFlag() throws Exception {
        assertFalse(hasServer("--no-mcp-file"));
    }
}
