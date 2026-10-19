package org.github.lscoughlin.jeorje.tools.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.plan.PlanMode;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.ToolRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PlanModeToolsTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<String> NAMES = List.of("read", "ls", "glob", "grep", "recall", "webfetch", "skill", "ask_user",
            "advisor", "todo", "plan_submit", "bash", "write", "edit", "patch", "spawn_agent", "diff", "worktree_merge");

    final List<String> ran = new ArrayList<>();
    final ToolContext context = new ToolContext(Path.of("."), new AgentId("root"), CancellationToken.none(), null,
            Clock.systemUTC());

    class Stub implements Tool {
        final String name;
        Stub(String name) { this.name = name; }
        @Override public ToolDefinition definition() { return new ToolDefinition(name, "stub", JSON.createObjectNode().put("type", "object")); }
        @Override public boolean readOnly() { return !name.equals("bash") && !name.equals("write"); }
        @Override public ToolResultData execute(JsonNode input, ToolContext c) { ran.add(name); return ToolResultData.ok("ran " + name); }
    }

    ToolRegistry full() {
        var b = ToolRegistry.builder();
        NAMES.forEach(n -> b.register(new Stub(n)));
        return b.build();
    }

    ToolResultData bash(ToolRegistry registry, String json) {
        return registry.execute(new ToolUse("1", "bash", JSON.readTree(json)), context);
    }

    @Test
    void registryHasOnlyThePlanModeTools() {
        var registry = PlanModeTools.restrict(full());
        assertEquals(PlanMode.TOOLS, Set.copyOf(registry.names()));
        for (var gone : List.of("write", "edit", "patch", "spawn_agent", "diff", "worktree_merge")) {
            assertTrue(registry.find(gone).isEmpty(), gone);
        }
        var result = registry.execute(new ToolUse("1", "write", JSON.createObjectNode()), context);
        assertTrue(result.error());
        assertTrue(result.output().contains("Unknown tool"));
        assertTrue(registry.find("plan_submit").isPresent());
    }

    @Test
    void bashRunsOnlyReadOnlyCommands() {
        var registry = PlanModeTools.restrict(full());
        assertFalse(bash(registry, "{\"command\":\"ls -la\"}").error());
        assertFalse(bash(registry, "{\"command\":\"git log --oneline | head -3\"}").error());
        assertEquals(List.of("bash", "bash"), ran);
        var refused = bash(registry, "{\"command\":\"rm -rf build\"}");
        assertTrue(refused.error());
        assertTrue(refused.output().contains("read-only"));
        assertTrue(bash(registry, "{\"command\":\"ls; touch x\"}").error());
        assertTrue(bash(registry, "{\"command\":\"cat a > b\"}").error());
        assertTrue(bash(registry, "{\"command\":\"ls\",\"background\":true}").error());
        assertTrue(bash(registry, "{}").error());
        assertEquals(2, ran.size());
        assertFalse(bash(registry, "{\"operation\":\"poll\",\"id\":\"p1\"}").error());
    }

    @Test
    void modeIsTheSameWithOrWithoutYolo() {
        // The restriction takes no permission mode, so --yolo has no input to change it.
        var a = PlanModeTools.restrict(full());
        var b = PlanModeTools.restrict(full());
        assertEquals(a.names(), b.names());
        assertTrue(bash(b, "{\"command\":\"rm file\"}").error());
        assertFalse(PlanMode.allows("write"));
    }
}
