package org.github.lscoughlin.jeorje.tools.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig.McpServer;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.permissions.NetworkPolicy;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class McpManagerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ToolContext CONTEXT =
            new ToolContext(Path.of("."), new AgentId("root"), CancellationToken.none(), null, Clock.systemUTC());

    private McpManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) manager.close();
    }

    private static McpServer fakeServer(int timeoutSeconds, boolean enabled) {
        var java = ProcessHandle.current().info().command().orElseThrow();
        return new McpServer("stdio", java, List.of("-cp", System.getProperty("java.class.path"),
                FakeMcpServer.class.getName()), null, Map.of(), List.of(), null, Map.of(), timeoutSeconds, enabled);
    }

    private static McpServer brokenServer() {
        return new McpServer("stdio", "/nonexistent/mcp-server", List.of(), null, Map.of(), List.of(), null,
                Map.of(), 5, true);
    }

    private McpManager connect(Map<String, McpServer> servers, Set<String> reserved) {
        var factory = McpManager.ClientFactory.standard(new NetworkPolicy(List.of(), List.of()), Path.of("."), System.getenv());
        manager = new McpManager(servers, factory, reserved);
        manager.connect();
        return manager;
    }

    @Test
    void toolsRoundTripThroughTheRegistry() throws Exception {
        var m = connect(Map.of("files", fakeServer(20, true)), Set.of());
        assertEquals(List.of("mcp__files__echo", "mcp__files__fail", "mcp__files__hang"),
                m.tools().stream().map(t -> t.definition().name()).toList());
        var registry = ToolRegistry.builder().registerAll(m.tools()).build();
        var ok = registry.execute(new ToolUse("1", "mcp__files__echo", JSON.readTree("{\"text\":\"hi\"}")), CONTEXT);
        assertFalse(ok.error());
        assertEquals("echo: hi", ok.output());
        var failed = registry.execute(new ToolUse("2", "mcp__files__fail", JSON.readTree("{}")), CONTEXT);
        assertTrue(failed.error());
        assertEquals("boom", failed.output());
    }

    @Test
    void invalidInputIsRejectedBeforeTheCall() throws Exception {
        var m = connect(Map.of("files", fakeServer(20, true)), Set.of());
        var registry = ToolRegistry.builder().registerAll(m.tools()).build();
        var result = registry.execute(new ToolUse("1", "mcp__files__echo", JSON.readTree("{\"text\":5}")), CONTEXT);
        assertTrue(result.error());
    }

    @Test
    void timeoutBecomesAnErrorResult() throws Exception {
        var m = connect(Map.of("files", fakeServer(1, true)), Set.of());
        var registry = ToolRegistry.builder().registerAll(m.tools()).build();
        var result = registry.execute(new ToolUse("1", "mcp__files__hang", JSON.readTree("{}")), CONTEXT);
        assertTrue(result.error());
        assertTrue(result.output().contains("did not answer"), result.output());
    }

    @Test
    void oneFailedServerDoesNotBlockTheOthers() {
        var servers = new LinkedHashMap<String, McpServer>();
        servers.put("bad", brokenServer());
        servers.put("good", fakeServer(20, true));
        servers.put("off", fakeServer(20, false));
        var m = connect(servers, Set.of());
        assertEquals(3, m.tools().size());
        var status = m.status().stream().collect(java.util.stream.Collectors.toMap(McpManager.ServerStatus::name, s -> s));
        assertFalse(status.get("bad").connected());
        assertTrue(status.get("bad").detail().startsWith("failed:"), status.get("bad").detail());
        assertTrue(status.get("good").connected());
        assertEquals("disabled", status.get("off").detail());
    }

    @Test
    void takenNamesAreSkipped() {
        var m = connect(Map.of("files", fakeServer(20, true)), Set.of("mcp__files__echo"));
        assertEquals(List.of("mcp__files__fail", "mcp__files__hang"),
                m.tools().stream().map(t -> t.definition().name()).toList());
    }

    @Test
    void toolNamesAreCleanedAndShortened() {
        assertEquals("mcp__a-b__do_it", McpManager.toolName("a-b", "do.it"));
        assertEquals(64, McpManager.toolName("s", "x".repeat(100)).length());
    }

    @Test
    void contentBlocksRenderAsText() throws Exception {
        var content = JSON.readTree("""
                [{"type":"text","text":"a"},{"type":"image","mimeType":"image/png","data":"AAAA"},
                 {"type":"resource","resource":{"uri":"file:///x","text":"body"}},
                 {"type":"resource_link","uri":"file:///y"}]""");
        assertEquals("a\n[image omitted: image/png, 4 base64 characters]\nbody\n[resource link: file:///y]",
                McpTool.render(content));
        assertEquals("(no output)", McpTool.render(JSON.createArrayNode()));
    }

    @Test
    void closeStopsEveryChild() {
        var m = connect(Map.of("files", fakeServer(20, true)), Set.of());
        var before = ProcessHandle.current().children().filter(h -> h.info().commandLine()
                .map(l -> l.contains(FakeMcpServer.class.getName())).orElse(false)).toList();
        assertEquals(1, before.size());
        m.close();
        before.forEach(h -> h.onExit().orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).join());
        assertTrue(before.stream().noneMatch(ProcessHandle::isAlive));
    }
}
