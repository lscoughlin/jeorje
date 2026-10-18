package org.github.lscoughlin.jeorje.tools.worktree;

import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.worktree.WorktreeManager;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.github.lscoughlin.jeorje.tools.registry.Required;

/** The {@code sync_abort} tool. It gives up the resolution. The subagent then ends with the outcome failed. */
public final class SyncAbortTool extends RecordTool<SyncAbortTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "sync_abort";

    /** The input of {@code sync_abort}. */
    public record Input(@Required @Describe("Why you cannot resolve the problem.") String reason) {}

    private final WorktreeManager manager;

    public SyncAbortTool(WorktreeManager manager) {
        super(NAME, "Give up the resolution. Jeorje aborts the merge or rebase and your task ends as failed. "
                + "The worktree and the branch stay.", Input.class, false);
        this.manager = manager;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) {
        if (input.reason() == null || input.reason().isBlank()) return ToolResultData.error("Give a reason.");
        if (manager.byAgent(context.agent()).isEmpty()) return ToolResultData.error("You have no worktree.");
        manager.signal(context.agent(), WorktreeManager.Resolution.ABORT, input.reason());
        return ToolResultData.ok("The sync will be aborted. End your turn now.");
    }
}
