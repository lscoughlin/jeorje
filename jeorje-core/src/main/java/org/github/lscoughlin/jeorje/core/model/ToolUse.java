package org.github.lscoughlin.jeorje.core.model;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** A request of the model to run a tool. */
public record ToolUse(String id, String tool, JsonNode input) implements Content {
    public ToolUse {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tool, "tool");
        input = input == null ? null : input.deepCopy();
    }

    @Override
    public JsonNode input() { return input == null ? null : input.deepCopy(); }
}
