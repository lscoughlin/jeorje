package org.github.lscoughlin.jeorje.tools.agents;

import java.util.List;
import org.github.lscoughlin.jeorje.core.port.PeerMessenger;
import org.github.lscoughlin.jeorje.core.port.Tool;
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
     * Like {@link #install(SubagentRuntime, String)}. A non-null messenger also adds {@code send_message} and
     * {@code ask_peer}, which reach agents on other instances of the swarm.
     */
    public static List<Tool> install(SubagentRuntime runtime, String sessionId, PeerMessenger messenger) {
        var base = new java.util.ArrayList<Tool>(List.of(new AgentStatusTool(runtime), new AgentMessageTool(runtime),
                new AgentAskTool(runtime), new AgentAnswerTool(runtime),
                new RecallTool(sessionId, runtime::nameOf)));
        if (messenger != null) {
            base.add(new SendMessageTool(messenger));
            base.add(new AskPeerTool(messenger));
        }
        var shared = List.copyOf(base);
        runtime.addSharedTools(shared);
        var all = new java.util.ArrayList<Tool>();
        all.add(new SpawnAgentTool(runtime));
        all.addAll(shared);
        return List.copyOf(all);
    }
}
