package org.github.lscoughlin.jeorje.tools.agents;

import java.nio.file.Path;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.subagent.Completion;
import org.github.lscoughlin.jeorje.core.subagent.SpawnException;
import org.github.lscoughlin.jeorje.core.subagent.SpawnMode;
import org.github.lscoughlin.jeorje.core.subagent.SpawnRequest;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.tools.registry.Allowed;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.github.lscoughlin.jeorje.tools.registry.Required;

/**
 * The {@code spawn_agent} tool. It starts a subagent. In {@code background} mode it returns the agent id at once.
 * In {@code wait} mode it blocks the caller and returns the completion summary.
 */
public final class SpawnAgentTool extends RecordTool<SpawnAgentTool.Input> {
    /** The name of the tool. */
    public static final String NAME = SubagentRuntime.SPAWN_TOOL;

    /** The input of {@code spawn_agent}. */
    public record Input(
            @Required @Describe("Short label that the UI shows.") String name,
            @Required @Describe("The instruction for the subagent. It must have all needed information, because the "
                    + "subagent does not see your history.") String task,
            @Describe("Tools for the subagent. The default is your tool set without spawn_agent.") List<String> tools,
            @Describe("Name of a subagent definition from .agents/agents/.") String type,
            @Describe("Model slug. The default is the default model.") String model,
            @Allowed({"worktree", "shared"}) @Describe("Isolation of the working directory.") String isolation,
            @Allowed({"background", "wait"}) @Describe("background returns at once. wait blocks and returns the "
                    + "result.") String mode,
            @Describe("Explicit working directory. A subagent with workdir gets no worktree.") String workdir) {}

    private final SubagentRuntime runtime;

    public SpawnAgentTool(SubagentRuntime runtime) {
        super(NAME, "Start a subagent that does one task. The subagent has its own history and a restricted tool "
                + "set. In background mode this call returns the agent id and the result comes later as an input "
                + "event. In wait mode this call returns the completion summary.", Input.class, false);
        this.runtime = runtime;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) throws Exception {
        SpawnMode mode;
        try {
            mode = SpawnMode.parse(input.mode());
        } catch (SpawnException e) {
            return ToolResultData.error(e.getMessage());
        }
        var workdir = input.workdir() == null || input.workdir().isBlank() ? null : Path.of(input.workdir());
        var request = new SpawnRequest(input.name(), input.task(), input.tools(), blankToNull(input.type()),
                blankToNull(input.model()), blankToNull(input.isolation()), mode, workdir, null);
        org.github.lscoughlin.jeorje.core.model.AgentId id;
        try {
            id = runtime.spawn(context.agent(), request);
        } catch (SpawnException e) {
            return ToolResultData.error("spawn_agent failed: " + e.getMessage());
        }
        var dropped = runtime.droppedTools(id);
        var note = dropped.isEmpty() ? "" : "\nTools that you do not have were not given: " + String.join(", ", dropped);
        if (mode == SpawnMode.BACKGROUND) {
            return ToolResultData.ok("Started subagent " + input.name() + " with agent id " + id
                    + ". Its completion summary comes later as an input event." + note);
        }
        Completion completion = runtime.await(id, context.cancel());
        var text = "Subagent " + completion.name() + " (" + completion.agent() + ") ended with the outcome "
                + completion.outcome().name().toLowerCase(java.util.Locale.ROOT) + ".\n" + completion.summary() + note;
        return completion.outcome() == Completion.Outcome.COMPLETED ? ToolResultData.ok(text)
                : ToolResultData.error(text);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
