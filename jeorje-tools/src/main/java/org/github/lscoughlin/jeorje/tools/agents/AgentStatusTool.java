package org.github.lscoughlin.jeorje.tools.agents;

import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;

/** The {@code agent_status} tool. It lists the agents of the session. */
public final class AgentStatusTool extends RecordTool<AgentStatusTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "agent_status";

    /** The input of {@code agent_status}: none. */
    public record Input() {}

    private final SubagentRuntime runtime;

    public AgentStatusTool(SubagentRuntime runtime) {
        super(NAME, "List the agents of the session with their state, the story or task, and the branch.",
                Input.class, true);
        this.runtime = runtime;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) {
        var out = new StringBuilder();
        for (var info : runtime.status()) {
            out.append(info.name()).append(" | id ").append(info.id()).append(" | ").append(info.state());
            if (info.waitingFor() != null) out.append(" (waiting for ").append(info.waitingFor()).append(')');
            if (info.storyId() != null) out.append(" | story ").append(info.storyId());
            if (info.branch() != null) out.append(" | branch ").append(info.branch());
            if (info.parent() != null) out.append(" | parent ").append(runtime.nameOf(info.parent().value()));
            if (!info.task().isEmpty()) out.append(" | task: ").append(oneLine(info.task(), 120));
            if (!info.lastOutput().isEmpty()) out.append(" | last: ").append(oneLine(info.lastOutput(), 80));
            out.append('\n');
        }
        return ToolResultData.ok(out.isEmpty() ? "No agents.\n" : out.toString());
    }

    private static String oneLine(String text, int max) {
        var t = text.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "..." : t;
    }
}
