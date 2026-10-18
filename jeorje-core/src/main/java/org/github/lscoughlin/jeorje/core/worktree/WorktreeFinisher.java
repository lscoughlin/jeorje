package org.github.lscoughlin.jeorje.core.worktree;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.subagent.Completion;
import org.github.lscoughlin.jeorje.core.subagent.SubagentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Does the cleanup by the parent when a subagent ends. Give it to the subagent runtime as an event consumer. It
 * writes the {@code worktree removed} note through the note sink.
 */
public final class WorktreeFinisher implements Consumer<SubagentEvent> {
    private static final Logger log = LoggerFactory.getLogger(WorktreeFinisher.class);

    private final WorktreeManager manager;
    private final BiConsumer<AgentId, String> notes;

    /** @param notes receives the parent agent and the note text for the history of the parent */
    public WorktreeFinisher(WorktreeManager manager, BiConsumer<AgentId, String> notes) {
        this.manager = manager;
        this.notes = notes;
    }

    @Override
    public void accept(SubagentEvent event) {
        if (!(event instanceof SubagentEvent.Finished finished)) return;
        var found = manager.byAgent(finished.agent());
        if (found.isEmpty()) return;
        var w = found.get();
        try {
            var result = manager.cleanup(w, finished.completion().outcome() == Completion.Outcome.COMPLETED);
            if (result.removed() && result.note() != null && w.parent != null) notes.accept(w.parent, result.note());
            else if (!result.removed()) {
                log.atInfo().log(() -> "Kept worktree " + w.path + ": " + result.keepReason());
            }
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "Cleanup of " + w.path + " failed: " + e.getMessage());
        }
    }
}
