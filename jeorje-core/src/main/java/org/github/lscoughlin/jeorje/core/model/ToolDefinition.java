package org.github.lscoughlin.jeorje.core.model;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** The name, description, and JSON Schema of a tool. */
public record ToolDefinition(String name, String description, JsonNode inputSchema) {
    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        inputSchema = inputSchema == null ? null : inputSchema.deepCopy();
    }

    @Override
    public JsonNode inputSchema() { return inputSchema == null ? null : inputSchema.deepCopy(); }
}
