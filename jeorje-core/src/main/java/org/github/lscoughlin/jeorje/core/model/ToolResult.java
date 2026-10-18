package org.github.lscoughlin.jeorje.core.model;

import java.util.Objects;

/** The result of a tool run. */
public record ToolResult(String toolUseId, String output, boolean error) implements Content {
    public ToolResult {
        Objects.requireNonNull(toolUseId, "toolUseId");
        Objects.requireNonNull(output, "output");
    }
}
