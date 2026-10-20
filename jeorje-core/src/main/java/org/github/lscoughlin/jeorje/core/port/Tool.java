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
     * Checks the input of a call against the input schema of the tool before the call runs. The default checks the
     * required fields, the types, and the other rules of {@link InputValidator}.
     *
     * @return the text of the error for the model, or empty when the input is valid
     */
    default java.util.Optional<String> check(JsonNode input) {
        var schema = definition().inputSchema();
        var errors = InputValidator.validate(schema, input);
        if (errors.isEmpty()) return java.util.Optional.empty();
        var text = "Invalid input for " + definition().name() + ": " + String.join("; ", errors);
        var empty = input == null || input.isNull() || input.isObject() && input.isEmpty();
        if (empty && schema != null && schema.path("required").isArray() && !schema.path("required").isEmpty()) {
            text += ". The arguments of the call were empty. Send the whole call again with all required fields.";
        }
        return java.util.Optional.of(text);
    }

    /**
     * Runs the tool. A thrown exception becomes an error result. The tool must stop soon
     * when {@code context.cancel()} is cancelled.
     */
    ToolResultData execute(JsonNode input, ToolContext context) throws Exception;
}
