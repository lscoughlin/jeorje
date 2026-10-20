package org.github.lscoughlin.jeorje.tools.plan;

import org.github.lscoughlin.jeorje.core.plan.PlanMode;
import org.github.lscoughlin.jeorje.tools.registry.ToolRegistry;

/**
 * Makes the tool registry of plan mode. The registry has only the tools of {@link PlanMode#TOOLS}.
 * {@code bash} is one of them. The option {@code --yolo} has no effect here: this class does not read
 * any permission mode.
 */
public final class PlanModeTools {
    private PlanModeTools() {}

    /** A registry with the plan mode tools from the full registry. Tools that the full registry does not have are missing. */
    public static ToolRegistry restrict(ToolRegistry full) {
        var builder = ToolRegistry.builder();
        for (var name : full.only(PlanMode.TOOLS).names()) {
            var tool = full.find(name).orElseThrow();
            builder.register(tool);
            builder.timeout(name, full.timeoutFor(name));
        }
        builder.maxOutputChars(full.maxOutputChars());
        return builder.build();
    }
}
