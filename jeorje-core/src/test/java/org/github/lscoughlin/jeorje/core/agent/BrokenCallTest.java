package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** A call whose arguments are cut off or are not valid JSON never runs. The model gets a clear error. */
class BrokenCallTest {
    @TempDir Path dir;

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"}},"
        + "\"required\":[\"title\"]}";

    private final AtomicInteger runs = new AtomicInteger();

    private Tool submit() {
        return new Tool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("plan_submit", "Submit", MAPPER.readTree(SCHEMA));
            }
            @Override public boolean readOnly() { return false; }
            @Override public ToolResultData execute(JsonNode input, ToolContext context) {
                runs.incrementAndGet();
                return ToolResultData.ok("Plan written.");
            }
        };
    }

    /** One reply with one tool call: the arguments arrive in two fragments and the reply ends with a reason. */
    private static Step reply(String id, String first, String second, String reason) {
        return new Step.Events(List.of(new LlmEvent.ToolUseStart(id, "plan_submit"),
            new LlmEvent.ToolUseInput(id, first), new LlmEvent.ToolUseInput(id, second), new LlmEvent.MessageStop(reason)));
    }

    private static String lastResult(Scripted llm) {
        var results = llm.requests.getLast().messages().stream().flatMap(m -> m.content().stream())
            .filter(ToolResult.class::isInstance).map(ToolResult.class::cast).toList();
        return results.getLast().output();
    }

    private Rig rig(Scripted llm) {
        return new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir), submit());
    }

    @Test
    void aCallCutOffByTheOutputLimitSaysSo() {
        var llm = new Scripted(reply("c1", "{\"title\":\"x\",\"stories\":[{\"id\":", "\"S1\",\"goal\":", "length"), text("ok"));
        rig(llm).agent.runTurn("go");
        assertEquals(0, runs.get());
        assertEquals("The arguments of your call to plan_submit were not valid JSON (42 characters were received). "
            + "The output limit of the model was reached, so the call was cut off. Make the call shorter or split the "
            + "work in steps. Send the call again.", lastResult(llm));
    }

    @Test
    void aCallThatEndsEarlyWithoutTheLimitSaysThatTheStreamEnded() {
        var llm = new Scripted(reply("c1", "{\"title\":", "\"x\"", "tool_calls"), text("ok"));
        rig(llm).agent.runTurn("go");
        assertEquals(0, runs.get());
        assertTrue(lastResult(llm).contains("The stream ended in the middle of the arguments. Send the call again."),
            lastResult(llm));
    }

    @Test
    void textThatIsNotJsonAndTwoJoinedCallsAreBroken() {
        var llm = new Scripted(reply("c1", "{\"title\": ", "oops}", "tool_calls"), text("ok"));
        rig(llm).agent.runTurn("go");
        assertTrue(lastResult(llm).contains("The arguments are not valid JSON. Send the call again."), lastResult(llm));
        var joined = new Scripted(reply("c1", "{\"title\":\"a\"}", "{\"title\":\"b\"}", "tool_calls"), text("ok"));
        rig(joined).agent.runTurn("go");
        assertEquals(0, runs.get(), "two calls in one text are not one call");
        assertTrue(lastResult(joined).startsWith("The arguments of your call to plan_submit were not valid JSON"));
        var array = new Scripted(reply("c1", "[1,", "2]", "tool_calls"), text("ok"));
        rig(array).agent.runTurn("go");
        assertTrue(lastResult(array).contains("The arguments are not a JSON object."), lastResult(array));
    }

    @Test
    void blankArgumentsAreAnEmptyCallAndNotABrokenOne() {
        var llm = new Scripted(reply("c1", "", "", "tool_calls"), text("ok"));
        rig(llm).agent.runTurn("go");
        assertEquals(0, runs.get());
        assertTrue(lastResult(llm).startsWith("Invalid input for plan_submit: input.title is required"));
        assertTrue(lastResult(llm).contains("The arguments of the call were empty."));
    }

    @Test
    void aGoodCallStillRuns() {
        var llm = new Scripted(reply("c1", "{\"title\":", "\"x\"}", "tool_calls"), text("ok"));
        rig(llm).agent.runTurn("go");
        assertEquals(1, runs.get());
        assertEquals("Plan written.", lastResult(llm));
    }

    @Test
    void twoBadStepsInARowGiveOneWarningAndAGoodStepStartsTheCountAgain() {
        var llm = new Scripted(
            reply("a1", "{\"title\":", "oops}", "tool_calls"),
            reply("a2", "{}", "", "tool_calls"),
            reply("a3", "{\"title\":", "\"x\"}", "tool_calls"),
            reply("a4", "{\"title\":", "oops}", "tool_calls"),
            reply("a5", "{\"title\":", "oops}", "tool_calls"),
            reply("a6", "{\"title\":", "oops}", "tool_calls"),
            text("done"));
        var rig = rig(llm);
        rig.agent.runTurn("go");
        var errors = rig.events(AgentEvent.Error.class);
        assertEquals(2, errors.size(), "one after a1+a2, and one after a4+a5; a6 is the third in a row and adds none");
        for (var e : errors) {
            assertEquals("The model sent a tool call with broken arguments 2 times in a row. The provider may be at "
                + "fault. Try another model or provider.", e.message());
        }
        assertEquals(1, runs.get());
    }
}
