package org.github.lscoughlin.jeorje.tools.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.files.EditTool;
import org.github.lscoughlin.jeorje.tools.files.ReadTool;
import org.github.lscoughlin.jeorje.tools.files.WriteTool;
import org.github.lscoughlin.jeorje.tools.memory.AskUserTool;
import org.github.lscoughlin.jeorje.tools.plan.PlanSubmitTool;
import org.github.lscoughlin.jeorje.tools.search.GrepTool;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The check of the input of a call, with the real schemas of the built-in tools. */
class ToolCheckTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String EMPTY = ". The arguments of the call were empty. Send the whole call again with all required fields.";

    /** A tool with the schema of a real input record. */
    private static Tool tool(String name, Class<? extends Record> input) {
        return new Tool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition(name, "d", SchemaGenerator.forRecord(input));
            }
            @Override public boolean readOnly() { return true; }
            @Override public ToolResultData execute(JsonNode in, ToolContext context) { return ToolResultData.ok(""); }
        };
    }

    private static String check(Tool tool, String json) {
        return tool.check(JSON.readTree(json)).orElse("");
    }

    @Test
    void anEmptyReadSaysThatTheArgumentsWereEmpty() {
        assertEquals("Invalid input for read: input.path is required" + EMPTY, check(tool("read", ReadTool.Input.class), "{}"));
    }

    @Test
    void aReadWithoutAPathNamesThePathOnly() {
        assertEquals("Invalid input for read: input.path is required",
            check(tool("read", ReadTool.Input.class), "{\"limit\":5}"));
    }

    @Test
    void anEmptyPlanSubmitNamesTheRequiredFields() {
        var text = check(tool("plan_submit", PlanSubmitTool.Input.class), "{}");
        for (var field : new String[] {"input.title is required", "input.goal is required", "input.stories is required"}) {
            assertTrue(text.contains(field), text);
        }
        assertTrue(text.endsWith(EMPTY), text);
    }

    @Test
    void aWrongTypeNamesTheField() {
        var text = check(tool("read", ReadTool.Input.class), "{\"path\":\"a\",\"limit\":\"ten\"}");
        assertTrue(text.contains("input.limit must be of type integer"), text);
    }

    @Test
    void aStoryWithoutRequiredFieldsIsNamed() {
        var text = check(tool("plan_submit", PlanSubmitTool.Input.class),
            "{\"title\":\"t\",\"goal\":\"g\",\"stories\":[{\"id\":\"S1\"}]}");
        assertTrue(text.contains("input.stories[0].title is required"), text);
        assertTrue(text.contains("input.stories[0].tasks is required"), text);
    }

    @Test
    void validCallsOfTheBuiltInToolsPass() {
        assertEquals("", check(tool("read", ReadTool.Input.class), "{\"path\":\"a.txt\"}"));
        assertEquals("", check(tool("read", ReadTool.Input.class), "{\"path\":\"a.txt\",\"offset\":3,\"limit\":10}"));
        assertEquals("", check(tool("write", WriteTool.Input.class), "{\"path\":\"a\",\"content\":\"\"}"));
        assertEquals("", check(tool("edit", EditTool.Input.class), "{\"path\":\"a\",\"oldString\":\"x\",\"newString\":\"\"}"));
        assertEquals("", check(tool("grep", GrepTool.Input.class), "{\"pattern\":\"x\"}"));
        assertEquals("", check(tool("ask_user", AskUserTool.Input.class), "{\"question\":\"Which?\"}"));
        assertEquals("", check(tool("ask_user", AskUserTool.Input.class),
            "{\"questions\":[{\"prompt\":\"p\",\"options\":[\"a\",{\"label\":\"b\"}]}]}"));
        assertEquals("", check(tool("plan_submit", PlanSubmitTool.Input.class),
            "{\"title\":\"t\",\"goal\":\"g\",\"stories\":[{\"id\":\"S1\",\"title\":\"t\",\"goal\":\"g\","
                + "\"context\":\"c\",\"acceptanceCriteria\":[\"a\"],\"tasks\":[{\"id\":\"S1.T1\",\"text\":\"x\"}]}]}"));
    }

    @Test
    void aToolWithNoRequiredFieldsAcceptsAnEmptyCall() {
        assertEquals("", check(tool("optional", NoRequired.class), "{}"));
    }

    record NoRequired(String a, Integer b) {}

    @Test
    void theRegistryGivesTheSameText() {
        var registry = ToolRegistry.builder().register(tool("read", ReadTool.Input.class)).build();
        var context = new ToolContext(java.nio.file.Path.of("."), new org.github.lscoughlin.jeorje.core.model.AgentId("a"),
            org.github.lscoughlin.jeorje.core.model.CancellationToken.none(), null, java.time.Clock.systemUTC());
        var result = registry.execute(new org.github.lscoughlin.jeorje.core.model.ToolUse("id", "read", JSON.readTree("{}")),
            context);
        assertTrue(result.error());
        assertEquals("Invalid input for read: input.path is required" + EMPTY, result.output());
    }
}
