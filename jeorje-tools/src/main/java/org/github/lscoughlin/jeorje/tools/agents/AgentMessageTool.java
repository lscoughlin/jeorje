package org.github.lscoughlin.jeorje.tools.agents;

import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.github.lscoughlin.jeorje.tools.registry.Required;

/** The {@code agent_message} tool. It sends a message to another agent. No answer is expected. */
public final class AgentMessageTool extends RecordTool<AgentMessageTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "agent_message";

    /** The input of {@code agent_message}. */
    public record Input(
            @Required @Describe("An agent name, an agent id, or a story id.") String to,
            @Required @Describe("The message text.") String text) {}

    private final SubagentRuntime runtime;

    public AgentMessageTool(SubagentRuntime runtime) {
        super(NAME, "Send a message to another agent. You get no answer. The agent sees it at its next step "
                + "boundary.", Input.class, false);
        this.runtime = runtime;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) {
        var result = runtime.message(context.agent(), input.to(), input.text());
        return result.ok() ? ToolResultData.ok(result.answer()) : AgentAskTool.failure(NAME, input.to(), result);
    }
}
