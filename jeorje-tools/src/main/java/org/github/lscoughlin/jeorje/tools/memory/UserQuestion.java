package org.github.lscoughlin.jeorje.tools.memory;

import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentId;

/**
 * A question event from an agent to the user.
 *
 * @param id the id that the answer must use
 * @param choices the choices, or an empty list for a free answer
 */
public record UserQuestion(String id, AgentId agent, String question, List<String> choices) {
    public UserQuestion { choices = List.copyOf(choices); }
}
