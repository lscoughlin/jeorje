package org.github.lscoughlin.jeorje.core.subagent;

import java.util.Optional;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;

/**
 * A sink for messages and questions to agents that a {@link SubagentRuntime} does not own. The runtime calls the
 * sink when it cannot resolve the target. A sink can reach an agent in another runtime, for example the root
 * agent of another session.
 */
public interface ExternalDelivery {
    /**
     * Delivers a message or a question.
     *
     * @param from the id of the sender
     * @param fromName the display name of the sender
     * @param to the id or name that the sender gave
     * @param text the text
     * @param question true when the sender waits for an answer
     * @param cancel stops the wait of a question
     * @return the result, or empty when this sink does not own the target
     */
    Optional<AskResult> deliver(AgentId from, String fromName, AgentId to, String text, boolean question,
                                CancellationToken cancel);
}
