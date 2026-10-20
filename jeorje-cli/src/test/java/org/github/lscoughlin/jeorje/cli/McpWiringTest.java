package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.github.lscoughlin.jeorje.testkit.StubEmbedder;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import tools.jackson.databind.json.JsonMapper;

/** The MCP servers of the config reach the tools of the real runtime. */
class McpWiringTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String config(String command) {
        var root = JSON.createObjectNode();
        var server = root.putObject("mcp").putObject("servers").putObject("echo");
        server.put("command", command);
        if (command.endsWith("java")) {
            server.putArray("args").add("-cp").add(System.getProperty("java.class.path"))
                    .add(FakeMcpEcho.class.getName());
        }
        server.put("timeoutSeconds", 20);
        var rule = root.putObject("permissions").putArray("rules").addObject();
        rule.put("tool", "mcp__echo__*").put("decision", "allow");
        return root.toString();
    }

    private static String java() {
        return ProcessHandle.current().info().command().orElseThrow();
    }

    private static Wiring.Runtime open(TempWorkspace home, TempWorkspace cwd, ScriptedLlmClient llm, String... more) {
        var env = new CliEnvironment(home.root(), cwd.root(), Map.of("OPENROUTER_API_KEY", "test-key"),
                System.out, System.err, (config, model, key) -> llm, config -> Optional.of(new StubEmbedder()), false);
        var args = new java.util.ArrayList<>(List.of("--dir", cwd.root().toString(), "--trust"));
        args.addAll(List.of(more));
        var options = CommandLine.populateCommand(new ContextWindowsTest.Holder(), args.toArray(String[]::new)).global;
        return Wiring.open(Wiring.loadConfig(options, env), options, env);
    }

    private static List<ProcessHandle> fakeServers() {
        return ProcessHandle.current().children().filter(h -> h.info().commandLine()
                .map(l -> l.contains(FakeMcpEcho.class.getName())).orElse(false)).toList();
    }

    @Test
    void theToolsReachTheModelAndACallReturnsItsResult() throws Exception {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            cwd.write(".jeorje/config.yaml", config(java()));
            var llm = new ScriptedLlmClient(Response.toolCall("c1", "mcp__echo__echo", "{\"text\":\"hi\"}"),
                    Response.text("done"));
            try (var runtime = open(home, cwd, llm)) {
                runtime.agent().runTurn("call the tool");
                var names = llm.requests().getFirst().tools().stream().map(t -> t.name()).toList();
                assertTrue(names.contains("mcp__echo__echo"), names.toString());
                assertTrue(llm.requests().get(1).messages().toString().contains("echo: hi"),
                        llm.requests().get(1).messages().toString());
                assertTrue(Wiring.mcpReport(runtime.mcp(), runtime.loaded().config(), false)
                        .contains("echo: connected"));
            }
        }
    }

    @Test
    void closingTheRuntimeStopsTheServer() throws Exception {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            cwd.write(".jeorje/config.yaml", config(java()));
            var running = new java.util.ArrayList<ProcessHandle>();
            try (var runtime = open(home, cwd, new ScriptedLlmClient())) {
                running.addAll(fakeServers());
                assertEquals(1, running.size());
            }
            running.forEach(h -> h.onExit().orTimeout(5, TimeUnit.SECONDS).join());
            assertTrue(running.stream().noneMatch(ProcessHandle::isAlive));
        }
    }

    @Test
    void noMcpStartsNothing() throws Exception {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            cwd.write(".jeorje/config.yaml", config(java()));
            var llm = new ScriptedLlmClient(Response.text("ok"));
            try (var runtime = open(home, cwd, llm, "--no-mcp")) {
                runtime.agent().runTurn("hello");
                assertNull(runtime.mcp());
                assertTrue(fakeServers().isEmpty());
                assertFalse(llm.requests().getFirst().tools().stream().anyMatch(t -> t.name().startsWith("mcp__")));
                assertTrue(Wiring.mcpReport(null, runtime.loaded().config(), true).contains("--no-mcp"));
            }
        }
    }

    @Test
    void aServerThatCannotStartDoesNotStopTheSession() throws Exception {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            cwd.write(".jeorje/config.yaml", config("/nonexistent/mcp-server"));
            var llm = new ScriptedLlmClient(Response.text("ok"));
            try (var runtime = open(home, cwd, llm)) {
                runtime.agent().runTurn("hello");
                assertTrue(runtime.mcp().tools().isEmpty());
                assertTrue(Wiring.mcpReport(runtime.mcp(), runtime.loaded().config(), false).contains("echo: failed"));
            }
        }
    }

    @Test
    void theReportSaysWhenNothingIsConfigured() throws Exception {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace();
             var runtime = open(home, cwd, new ScriptedLlmClient())) {
            assertTrue(Wiring.mcpReport(runtime.mcp(), runtime.loaded().config(), false).contains("No MCP servers"));
        }
    }
}
