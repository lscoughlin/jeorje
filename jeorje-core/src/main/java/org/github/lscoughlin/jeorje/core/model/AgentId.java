package org.github.lscoughlin.jeorje.core.model;

import java.util.Objects;

/** The id of one agent. */
public record AgentId(String value) {
    public AgentId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("agent id is blank");
    }

    @Override
    public String toString() { return value; }
}
