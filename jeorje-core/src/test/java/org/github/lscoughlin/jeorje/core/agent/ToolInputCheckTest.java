package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** The agent checks the input of every call, so a call with empty arguments never runs the tool. */
class ToolInputCheckTest {
    @TempDir Path dir;

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},"
        + "\"limit\":{\"type\":\"integer\"}},\"required\":[\"path\"],\"additionalProperties\":false}";

    private final AtomicInteger runs = new AtomicInteger();

    private Tool reader() {
        return new Tool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("read", "Read", MAPPER.readTree(SCHEMA));
            }
            @Override public boolean readOnly() { return true; }
            @Override public ToolResultData execute(JsonNode input, ToolContext context) {
                runs.incrementAndGet();
                return ToolResultData.ok("content of " + input.path("path").asString());
            }
        };
    }

    private String lastResult(Scripted llm) {
        var results = llm.requests.getLast().messages().stream().flatMap(m -> m.content().stream())
            .filter(ToolResult.class::isInstance).map(ToolResult.class::cast).toList();
        return results.getLast().output();
    }

    @Test
    void emptyArgumentsNeverReachTheTool() {
        var llm = new Scripted(calls("c1", "read", "{}"), text("ok"));
        var rig = new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir), reader());
        rig.agent.runTurn("go");
        assertEquals(0, runs.get());
        assertEquals("Invalid input for read: input.path is required. The arguments of the call were empty. "
            + "Send the whole call again with all required fields.", lastResult(llm));
    }

    @Test
    void aMissingFieldIsNamedWithoutTheSentenceAboutEmptyArguments() {
        var llm = new Scripted(calls("c1", "read", "{\"limit\":5}"), text("ok"));
        var rig = new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir), reader());
        rig.agent.runTurn("go");
        assertEquals(0, runs.get());
        assertEquals("Invalid input for read: input.path is required", lastResult(llm));
    }

    @Test
    void aValidCallRuns() {
        var llm = new Scripted(calls("c1", "read", "{\"path\":\"a.txt\",\"limit\":5}"), text("ok"));
        var rig = new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir), reader());
        rig.agent.runTurn("go");
        assertEquals(1, runs.get());
        assertEquals("content of a.txt", lastResult(llm));
    }

    @Test
    void aToolFromTheTestSupportWithoutASchemaStillRuns() {
        var llm = new Scripted(calls("c1", "ping", "{}"), text("ok"));
        var rig = new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir),
            tool("ping", true, (in, ctx) -> ToolResultData.ok("pong")));
        rig.agent.runTurn("go");
        assertEquals("pong", lastResult(llm));
    }
}
