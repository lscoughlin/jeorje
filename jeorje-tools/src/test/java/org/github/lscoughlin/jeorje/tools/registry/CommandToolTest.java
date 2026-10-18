package org.github.lscoughlin.jeorje.tools.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CommandToolTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    static ToolContext context(TempWorkspace ws) {
        return new ToolContext(ws.root(), new AgentId("a"), CancellationToken.none(), null, Clock.systemUTC());
    }

    static ToolUse use(String tool, String json) {
        return new ToolUse("id", tool, JSON.readTree(json));
    }

    @Test
    void readsStdinAndWritesStdout() throws Exception {
        try (var ws = new TempWorkspace()) {
            var script = ws.write("tool.sh", "#!/bin/sh\ncat\n");
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
            var entry = Map.<String, Object>of(
                    "name", "catter", "description", "Echoes input", "readOnly", true,
                    "command", List.of(script.toString()),
                    "schema", Map.of("type", "object", "required", List.of("q"),
                            "properties", Map.of("q", Map.of("type", "string"))));
            var tool = CommandTool.fromConfig(entry);
            assertTrue(tool.readOnly());
            var registry = ToolRegistry.builder().register(tool).build();
            var ok = registry.execute(use("catter", "{\"q\":\"hi\"}"), context(ws));
            assertFalse(ok.error(), ok.output());
            assertEquals("hi", JSON.readTree(ok.output()).get("q").asString());
            var invalid = registry.execute(use("catter", "{}"), context(ws));
            assertTrue(invalid.error());
            assertTrue(invalid.output().contains("input.q is required"));
        }
    }

    @Test
    void nonZeroExitIsAnError() throws Exception {
        try (var ws = new TempWorkspace()) {
            var tool = CommandTool.fromConfig(Map.of("name", "fail", "description", "d",
                    "command", List.of("sh", "-c", "echo oops >&2; exit 3")));
            var registry = ToolRegistry.builder().register(tool).build();
            var result = registry.execute(use("fail", "{}"), context(ws));
            assertTrue(result.error());
            assertTrue(result.output().contains("code 3"));
            assertTrue(result.output().contains("oops"));
        }
    }

    @Test
    void failsCleanlyForMissingExecutable() throws Exception {
        try (var ws = new TempWorkspace()) {
            var tool = CommandTool.fromConfig(Map.of("name", "ghost", "command", List.of("/no/such/program")));
            var result = ToolRegistry.builder().register(tool).build().execute(use("ghost", "{}"), context(ws));
            assertTrue(result.error());
            assertTrue(result.output().contains("Cannot start"));
        }
    }

    @Test
    void timeoutKillsProcess() throws Exception {
        try (var ws = new TempWorkspace()) {
            var tool = CommandTool.fromConfig(Map.of("name", "sleepy", "command", List.of("sleep", "30"),
                    "timeoutSeconds", 1));
            var start = System.nanoTime();
            var result = ToolRegistry.builder().register(tool).build().execute(use("sleepy", "{}"), context(ws));
            assertTrue(result.error());
            assertTrue(result.output().contains("timed out"), result.output());
            assertTrue((System.nanoTime() - start) / 1_000_000_000L < 10);
        }
    }

    @Test
    void doesNotPassSecretsInEnvironment() throws Exception {
        try (var ws = new TempWorkspace()) {
            var tool = CommandTool.fromConfig(Map.of("name", "envdump", "command", List.of("env")));
            var result = ToolRegistry.builder().register(tool).build().execute(use("envdump", "{}"), context(ws));
            assertFalse(result.error(), result.output());
            assertFalse(result.output().toUpperCase().contains("KEY="));
            assertFalse(result.output().contains("TOKEN"));
        }
    }

    @Test
    void configNeedsNameAndCommand() {
        assertThrows(IllegalArgumentException.class, () -> CommandTool.fromConfig(Map.of("command", "ls")));
        assertThrows(IllegalArgumentException.class, () -> CommandTool.fromConfig(Map.of("name", "x")));
    }
}
