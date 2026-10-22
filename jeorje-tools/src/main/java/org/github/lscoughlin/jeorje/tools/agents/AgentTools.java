package org.github.lscoughlin.jeorje.tools.agents;

import java.util.List;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.subagent.ExternalDelivery;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.tools.memory.RecallTool;

/** Makes the tools of the subagent runtime. */
public final class AgentTools {
    private AgentTools() {}

    /**
     * Makes the agent tools and {@code recall}, and adds all of them except {@code spawn_agent} to the shared tools
     * of the runtime. Every subagent gets the shared tools. {@code recall} searches the full index of the session,
     * and its hits show the name of the source agent.
     *
     * @return all tools, with {@code spawn_agent} first. The caller gives them to the root agent.
     */
    public static List<Tool> install(SubagentRuntime runtime, String sessionId) {
        return install(runtime, sessionId, null);
    }

    /**
     * Like {@link #install(SubagentRuntime, String)}, and installs a sink that {@code agent_message} and
     * {@code agent_ask} use for a target that the runtime does not own.
     *
     * @param external the sink, or null for none
     */
    public static List<Tool> install(SubagentRuntime runtime, String sessionId, ExternalDelivery external) {
        if (external != null) runtime.setExternalDelivery(external);
        var shared = List.<Tool>of(new AgentStatusTool(runtime), new AgentMessageTool(runtime),
                new AgentAskTool(runtime), new AgentAnswerTool(runtime),
                new RecallTool(sessionId, runtime::nameOf));
        runtime.addSharedTools(shared);
        var all = new java.util.ArrayList<Tool>();
        all.add(new SpawnAgentTool(runtime));
        all.addAll(shared);
        return List.copyOf(all);
    }
}
