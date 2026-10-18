package org.github.lscoughlin.jeorje.tools.worktree;

import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.github.lscoughlin.jeorje.tools.registry.Required;

/** The {@code worktree_merge} tool. It runs the completion sequence for a subagent branch. */
public final class WorktreeMergeTool extends RecordTool<WorktreeMergeTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "worktree_merge";

    /** The input of {@code worktree_merge}. */
    public record Input(
            @Required @Describe("The agent name, the agent id, or the branch.") String agent,
            @Describe("The strategy: merge, squash, or rebase. The default is the configured strategy.")
            String strategy) {}

    private final WorktreeCommands commands;

    public WorktreeMergeTool(WorktreeCommands commands) {
        super(NAME, "Merge the branch of a subagent into its source branch. Jeorje syncs from the source first, "
                + "runs the verification, and then merges.", Input.class, false);
        this.commands = commands;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) {
        var outcome = commands.merge(input.agent(), input.strategy());
        return outcome.ok() ? ToolResultData.ok(outcome.message()) : ToolResultData.error(outcome.message());
    }
}
