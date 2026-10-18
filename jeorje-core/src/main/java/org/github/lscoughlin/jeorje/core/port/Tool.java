package org.github.lscoughlin.jeorje.core.port;

import tools.jackson.databind.JsonNode;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;

/** Port for a tool that the model can call. */
public interface Tool {
    /** The name, description, and input schema. */
    ToolDefinition definition();

    /** True if the tool can run at the same time as other read-only tools. */
    boolean readOnly();

    /**
     * Runs the tool. A thrown exception becomes an error result. The tool must stop soon
     * when {@code context.cancel()} is cancelled.
     */
    ToolResultData execute(JsonNode input, ToolContext context) throws Exception;
}
