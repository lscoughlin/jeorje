package org.github.lscoughlin.jeorje.core.model;

import java.util.List;
import java.util.Objects;
import org.github.lscoughlin.jeorje.core.state.StateUpdate;

/**
 * The output of a tool run.
 *
 * @param output the text for the model
 * @param error true if the run failed
 * @param updates changes to the state of the agent. The agent loop applies them in order.
 */
public record ToolResultData(String output, boolean error, List<StateUpdate> updates) {
    public ToolResultData {
        Objects.requireNonNull(output, "output");
        updates = updates == null ? List.of() : List.copyOf(updates);
    }

    public ToolResultData(String output, boolean error) { this(output, error, List.of()); }

    public static ToolResultData ok(String output) { return new ToolResultData(output, false); }

    public static ToolResultData ok(String output, List<StateUpdate> updates) {
        return new ToolResultData(output, false, updates);
    }

    public static ToolResultData error(String message) { return new ToolResultData(message, true); }

    /** The same result with other output text. The updates stay. */
    public ToolResultData withOutput(String text) { return new ToolResultData(text, error, updates); }
}
