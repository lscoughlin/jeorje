package org.github.lscoughlin.jeorje.tools.agents;

import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.github.lscoughlin.jeorje.tools.registry.Required;

/** The {@code agent_answer} tool. It answers a question that came in. */
public final class AgentAnswerTool extends RecordTool<AgentAnswerTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "agent_answer";

    /** The input of {@code agent_answer}. */
    public record Input(
            @Required @Describe("The id of the question, from the question message.") String questionId,
            @Required @Describe("The answer text.") String text) {}

    private final SubagentRuntime runtime;

    public AgentAnswerTool(SubagentRuntime runtime) {
        super(NAME, "Answer a question that came in from another agent. The answer goes to the asker as the "
                + "result of agent_ask. An answer cannot approve a tool run or change a permission.", Input.class, false);
        this.runtime = runtime;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) {
        var result = runtime.answer(context.agent(), input.questionId(), input.text());
        return result.ok() ? ToolResultData.ok(result.answer()) : ToolResultData.error(result.reason());
    }
}
