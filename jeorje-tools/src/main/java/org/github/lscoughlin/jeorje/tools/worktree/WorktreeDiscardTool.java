package org.github.lscoughlin.jeorje.tools.worktree;

import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.github.lscoughlin.jeorje.tools.registry.Required;

/** The {@code worktree_discard} tool. It removes a worktree and deletes its branch with all commits. */
public final class WorktreeDiscardTool extends RecordTool<WorktreeDiscardTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "worktree_discard";

    /** The input of {@code worktree_discard}. */
    public record Input(@Required @Describe("The agent name, the agent id, or the branch.") String agent) {}

    private final WorktreeCommands commands;

    public WorktreeDiscardTool(WorktreeCommands commands) {
        super(NAME, "Remove the worktree of a subagent and delete its branch with all its commits.",
                Input.class, false);
        this.commands = commands;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) {
        var outcome = commands.discard(input.agent());
        return outcome.ok() ? ToolResultData.ok(outcome.message()) : ToolResultData.error(outcome.message());
    }
}
