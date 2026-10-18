package org.github.lscoughlin.jeorje.tools.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.github.lscoughlin.jeorje.core.compaction.ModelRoles;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.core.port.Tool;

/** Builds the memory tools. */
public final class MemoryTools {
    private MemoryTools() {}

    /**
     * Makes the memory tools. {@code advisor} is in the list only when an advisor model is set in
     * the roles and a client and a context source are present.
     */
    public static List<Tool> create(ModelRoles roles, String sessionId, Function<String, String> agentNames,
                                    TodoTool todo, UserQuestionBroker broker, LlmClient advisorClient,
                                    Function<AgentId, AdvisorContext> contexts) {
        var tools = new ArrayList<Tool>();
        tools.add(new RecallTool(sessionId, agentNames));
        tools.add(todo);
        tools.add(new AskUserTool(broker));
        if (roles != null && roles.advisorToolEnabled() && advisorClient != null && contexts != null) {
            tools.add(new AdvisorTool(advisorClient, contexts));
        }
        return List.copyOf(tools);
    }
}
