package org.github.lscoughlin.jeorje.core.model;

import java.util.List;
import java.util.Objects;

/** The messages between one input and the final answer of the model. */
public record Turn(AgentId agent, int number, List<Message> messages) {
    public Turn {
        Objects.requireNonNull(agent, "agent");
        messages = List.copyOf(messages);
    }

    /** The id of the turn: {@code <agentId>:<number>}. */
    public String id() { return agent.value() + ":" + number; }
}
