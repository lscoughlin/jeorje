package org.github.lscoughlin.jeorje.tools.registry;

import java.util.Objects;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class for a tool with a typed input. Jackson 3 makes the JSON Schema from the input
 * record. The class converts the JSON input to the record before it calls {@link #run}.
 */
public abstract class RecordTool<I extends Record> implements Tool {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final ToolDefinition definition;
    private final Class<I> inputType;
    private final boolean readOnly;

    protected RecordTool(String name, String description, Class<I> inputType, boolean readOnly) {
        this.inputType = Objects.requireNonNull(inputType, "inputType");
        this.readOnly = readOnly;
        this.definition = new ToolDefinition(name, description, SchemaGenerator.forRecord(inputType));
    }

    @Override
    public final ToolDefinition definition() {
        return definition;
    }

    @Override
    public final boolean readOnly() {
        return readOnly;
    }

    @Override
    public final ToolResultData execute(JsonNode input, ToolContext context) throws Exception {
        I typed;
        try {
            typed = JSON.treeToValue(input == null ? JSON.createObjectNode() : input, inputType);
        } catch (tools.jackson.core.JacksonException e) {
            return ToolResultData.error("Invalid input for " + definition.name() + ": " + e.getOriginalMessage());
        }
        return run(typed, context);
    }

    /** Runs the tool with a valid typed input. */
    protected abstract ToolResultData run(I input, ToolContext context) throws Exception;
}
