package org.github.lscoughlin.jeorje.tools.plan;

import java.time.Duration;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.plan.PlanMode;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.TimedTool;
import org.github.lscoughlin.jeorje.tools.registry.ToolRegistry;
import tools.jackson.databind.JsonNode;

/**
 * Makes the tool registry of plan mode. The registry has only the tools of {@link PlanMode#TOOLS}.
 * {@code bash} runs only commands on the read-only allow list. The option {@code --yolo} has no
 * effect here: this class does not read any permission mode.
 */
public final class PlanModeTools {
    private PlanModeTools() {}

    /** A registry with the plan mode tools from the full registry. Tools that the full registry does not have are missing. */
    public static ToolRegistry restrict(ToolRegistry full) {
        var builder = ToolRegistry.builder();
        for (var name : full.only(PlanMode.TOOLS).names()) {
            var tool = full.find(name).orElseThrow();
            builder.register(name.equals("bash") ? new ReadOnlyBash(tool) : tool);
            builder.timeout(name, full.timeoutFor(name));
        }
        builder.maxOutputChars(full.maxOutputChars());
        return builder.build();
    }

    /** Wraps {@code bash}. It refuses a command that is not read-only. */
    static final class ReadOnlyBash implements TimedTool {
        private final Tool delegate;

        ReadOnlyBash(Tool delegate) { this.delegate = delegate; }

        @Override
        public ToolDefinition definition() { return delegate.definition(); }

        @Override
        public boolean readOnly() { return false; }

        @Override
        public Duration timeout() { return delegate instanceof TimedTool timed ? timed.timeout() : null; }

        @Override
        public ToolResultData execute(JsonNode input, ToolContext context) throws Exception {
            var operation = input.path("operation").asString("run");
            if (operation.equals("run")) {
                var command = input.path("command").asString("");
                if (!PlanMode.bashAllowed(command)) {
                    return ToolResultData.error("Plan mode: bash runs only read-only commands (ls, cat, grep, git log, "
                            + "and similar). This command is refused. Use the read tools, or leave plan mode.");
                }
                if (input.path("background").asBoolean(false)) {
                    return ToolResultData.error("Plan mode: bash does not run background commands.");
                }
            }
            return delegate.execute(input, context);
        }
    }
}
