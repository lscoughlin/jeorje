package org.github.lscoughlin.jeorje.tools.worktree;

import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.worktree.WorktreeManager;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;

/** The {@code sync_done} tool. It ends the resolution of a sync conflict or of a failed verification. */
public final class SyncDoneTool extends RecordTool<SyncDoneTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "sync_done";

    /** The input of {@code sync_done}. */
    public record Input(@Describe("Short notes about how you resolved the problem.") String notes) {}

    private final WorktreeManager manager;

    public SyncDoneTool(WorktreeManager manager) {
        super(NAME, "Call this when you resolved the sync conflicts or fixed the failing verification in your "
                + "worktree. Jeorje checks that no conflict markers remain, stages all files, and commits.",
                Input.class, false);
        this.manager = manager;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) {
        var found = manager.byAgent(context.agent());
        if (found.isEmpty()) return ToolResultData.error("You have no worktree. sync_done is not needed.");
        var result = manager.syncDone(found.get());
        return switch (result.kind()) {
            case DONE -> {
                manager.signal(context.agent(), WorktreeManager.Resolution.DONE, input.notes());
                yield ToolResultData.ok(result.message() + " End your turn now.");
            }
            case CONFLICT, MARKERS, ERROR -> ToolResultData.error(result.message());
        };
    }
}
