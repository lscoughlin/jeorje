package org.github.lscoughlin.jeorje.tools.registry;

import java.time.Duration;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Wraps a tool and limits one string property of its schema to a set of values. */
final class ConstrainedTool implements TimedTool {
    private final Tool delegate;
    private final ToolDefinition definition;

    ConstrainedTool(Tool delegate, String property, List<String> values) {
        this.delegate = delegate;
        var base = delegate.definition();
        var schema = base.inputSchema();
        if (schema == null || !(schema.path("properties").path(property) instanceof ObjectNode node)) {
            throw new IllegalArgumentException(base.name() + " has no property " + property);
        }
        var allowed = node.putArray("enum");
        values.forEach(allowed::add);
        this.definition = new ToolDefinition(base.name(), base.description(), schema);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public boolean readOnly() {
        return delegate.readOnly();
    }

    @Override
    public ToolResultData execute(JsonNode input, ToolContext context) throws Exception {
        return delegate.execute(input, context);
    }

    @Override
    public Duration timeout() {
        return delegate instanceof TimedTool timed ? timed.timeout() : null;
    }
}
