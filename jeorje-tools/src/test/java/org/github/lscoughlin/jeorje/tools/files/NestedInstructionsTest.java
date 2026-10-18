package org.github.lscoughlin.jeorje.tools.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.permissions.ToolPermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.state.AgentState;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.github.lscoughlin.jeorje.tools.registry.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NestedInstructionsTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    TempWorkspace ws;
    AgentState state;
    ToolRegistry registry;

    @BeforeEach
    void setUp() {
        ws = new TempWorkspace();
        state = new AgentState();
        var policy = ToolPermissionPolicy.builder(ws.root()).build();
        var nested = new NestedInstructions(100);
        var settings = new DirectoryContext.Settings(java.util.List.of("README.md"), 2000, 20,
                FrontMatter.Limits.defaults());
        registry = ToolRegistry.builder().register(new ReadTool(policy, state, settings, nested))
                .register(new WriteTool(policy, state, nested)).register(new EditTool(policy, state, nested)).build();
        ws.write("AGENTS.md", "ROOT-RULE");
        ws.write("sub/AGENTS.md", "SUB-RULE");
        ws.write("sub/deeper/AGENTS.md", "DEEP-RULE");
        ws.write("sub/a.txt", "a\n");
        ws.write("sub/deeper/b.txt", "b\n");
        ws.write("sub/deeper/c.txt", "c\n");
        ws.write("top.txt", "t\n");
    }

    @AfterEach
    void tearDown() { ws.close(); }

    ToolResultData call(String agent, String tool, String json) {
        var context = new ToolContext(ws.root(), new AgentId(agent), CancellationToken.none(), null, Clock.systemUTC());
        var result = registry.execute(new ToolUse("id", tool, JSON.readTree(json)), context);
        state.apply(result.updates());
        return result;
    }

    @Test
    void closestFileComesLastAndTheRootFileIsNotRepeated() {
        var text = call("a", "read", "{\"path\":\"sub/deeper/b.txt\"}").output();
        assertTrue(text.indexOf("SUB-RULE") >= 0 && text.indexOf("SUB-RULE") < text.indexOf("DEEP-RULE"), text);
        assertTrue(text.indexOf("DEEP-RULE") < text.indexOf("1\tb"), text);
        assertFalse(text.contains("ROOT-RULE"));
        assertFalse(call("a", "read", "{\"path\":\"top.txt\"}").output().contains("ROOT-RULE"));
    }

    @Test
    void eachAgentGetsEachFileOnce() {
        assertTrue(call("a", "read", "{\"path\":\"sub/a.txt\"}").output().contains("SUB-RULE"));
        assertFalse(call("a", "read", "{\"path\":\"sub/a.txt\"}").output().contains("SUB-RULE"));
        var deeper = call("a", "read", "{\"path\":\"sub/deeper/b.txt\"}").output();
        assertFalse(deeper.contains("SUB-RULE"), "already delivered");
        assertTrue(deeper.contains("DEEP-RULE"));
        assertTrue(call("b", "read", "{\"path\":\"sub/a.txt\"}").output().contains("SUB-RULE"), "another agent");
    }

    @Test
    void writeAndEditDeliverTheFilesToo() {
        var written = call("w", "write", "{\"path\":\"sub/deeper/new.txt\",\"content\":\"x\\n\"}").output();
        assertTrue(written.contains("SUB-RULE") && written.contains("DEEP-RULE"), written);
        assertTrue(written.contains("Created"), written);
        call("e", "read", "{\"path\":\"sub/deeper/c.txt\"}");
        var edited = call("e", "edit", "{\"path\":\"sub/deeper/c.txt\",\"oldString\":\"c\",\"newString\":\"d\"}").output();
        assertFalse(edited.contains("DEEP-RULE"), "the read call delivered it already");
        var other = call("f", "read", "{\"path\":\"sub/deeper/c.txt\"}");
        call("f", "edit", "{\"path\":\"sub/deeper/c.txt\",\"oldString\":\"d\",\"newString\":\"e\"}");
        assertTrue(other.output().contains("DEEP-RULE"));
    }

    @Test
    void aFileIsCutAtMaxBytes() {
        ws.write("sub/AGENTS.md", "z".repeat(500));
        var text = call("a", "read", "{\"path\":\"sub/a.txt\"}").output();
        assertTrue(text.contains("[This file is cut at 100 bytes.]"), text);
        assertEquals(-1, text.indexOf("z".repeat(101)));
    }

    @Test
    void readingTheNestedFileItselfDoesNotRepeatIt() {
        var text = call("a", "read", "{\"path\":\"sub/AGENTS.md\"}").output();
        assertEquals(1, text.split("SUB-RULE", -1).length - 1, text);
    }
}
