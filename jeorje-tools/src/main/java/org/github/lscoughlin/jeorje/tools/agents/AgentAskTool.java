package org.github.lscoughlin.jeorje.tools.agents;

import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.subagent.AskResult;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.github.lscoughlin.jeorje.tools.registry.Required;

/** The {@code agent_ask} tool. It asks another agent a question and waits for the answer. */
public final class AgentAskTool extends RecordTool<AgentAskTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "agent_ask";

    /** The input of {@code agent_ask}. */
    public record Input(
            @Required @Describe("An agent name, an agent id, or a story id.") String to,
            @Required @Describe("The question.") String question) {}

    private final SubagentRuntime runtime;

    public AgentAskTool(SubagentRuntime runtime) {
        super(NAME, "Ask another agent a question and wait for the answer. The call fails with a reason "
                + "(timeout, deadlock, busy, not started, ended) when no answer can come.", Input.class, false);
        this.runtime = runtime;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) {
        var result = runtime.ask(context.agent(), input.to(), input.question(), context.cancel());
        return result.ok() ? ToolResultData.ok(result.answer()) : failure(NAME, input.to(), result);
    }

    /** The error result for a failed ask or message. */
    static ToolResultData failure(String tool, String to, AskResult result) {
        var sb = new StringBuilder(tool).append(" failed: ").append(result.reason());
        sb.append(switch (result.reason()) {
            case AskResult.TIMEOUT -> ". No answer came in time. Continue without the answer.";
            case AskResult.DEADLOCK -> ". That agent waits for you, directly or through other agents.";
            case AskResult.BUSY -> ". That agent is in an internal wait and cannot answer now.";
            case AskResult.NOT_STARTED -> ". That agent did not start.";
            case AskResult.ENDED -> ". The agent " + to + " has ended.";
            case AskResult.LIMIT -> ". You asked the maximum number of questions in this task.";
            case AskResult.OPEN_ASK -> ". You already have an open question.";
            case AskResult.CANCELLED -> ". The call was cancelled.";
            default -> ".";
        });
        if (result.summary() != null) {
            sb.append("\nCompletion summary of ").append(to)
                .append(" (use its keywords with recall):\n").append(result.summary());
        }
        return ToolResultData.error(sb.toString());
    }
}
