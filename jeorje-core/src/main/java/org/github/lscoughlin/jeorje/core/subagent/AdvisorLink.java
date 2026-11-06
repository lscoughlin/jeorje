package org.github.lscoughlin.jeorje.core.subagent;

import java.util.List;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;

/**
 * The link from the subagent runtime to the advisor and to the todo lists of the agents. The turn watch uses
 * it to read the todos of a subagent and to ask the advisor for help.
 */
public interface AdvisorLink {
    /** The current todo list of an agent. The list is empty before the first call of the agent. */
    List<TodoItem> todos(AgentId agent);

    /**
     * Sends a help request to the advisor. The advisor sees the working context of the agent. The advisor
     * may update the todo list of the agent as part of the answer.
     *
     * @return the advice, or empty when the advisor is off or gave no answer
     */
    Optional<String> advise(AgentId agent, String request, CancellationToken cancel);
}
