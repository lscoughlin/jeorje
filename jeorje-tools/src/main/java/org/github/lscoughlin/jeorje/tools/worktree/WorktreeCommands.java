package org.github.lscoughlin.jeorje.tools.worktree;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.worktree.WorktreeCompletion;
import org.github.lscoughlin.jeorje.core.worktree.WorktreeManager;

/**
 * The data and actions behind the commands {@code /worktrees}, {@code /merge}, and {@code /discard}. The tools
 * {@code worktree_merge} and {@code worktree_discard} use the same actions.
 */
public final class WorktreeCommands {
    private final WorktreeManager manager;
    private final WorktreeCompletion completion;
    private final BiConsumer<AgentId, String> notes;

    /** @param notes receives the parent agent and the {@code worktree removed} note after a manual merge */
    public WorktreeCommands(WorktreeManager manager, WorktreeCompletion completion, BiConsumer<AgentId, String> notes) {
        this.manager = manager;
        this.completion = completion;
        this.notes = notes == null ? (a, n) -> {} : notes;
    }

    /** The rows of {@code /worktrees}. */
    public List<WorktreeManager.Row> list() { return manager.rows(); }

    /** The text of {@code /worktrees}: one line for each worktree. */
    public String listText() {
        var rows = list();
        if (rows.isEmpty()) return "No worktrees.";
        var out = new StringBuilder();
        for (var r : rows) {
            out.append(r.name()).append(" | ").append(r.branch()).append(" | ").append(r.path()).append(" | ")
                .append(r.state()).append('\n');
        }
        return out.toString();
    }

    /** {@code /merge <agent>} and {@code worktree_merge}. Returns the message for the user or the model. */
    public Outcome merge(String agent, String strategy) {
        var found = manager.find(agent);
        if (found.isEmpty()) return new Outcome(false, "No worktree for '" + agent + "'.");
        try {
            var result = completion.manualMerge(found.get(), strategy);
            if (result.note() != null && found.get().parent != null) notes.accept(found.get().parent, result.note());
            return new Outcome(result.merged(), result.message());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(false, "Interrupted.");
        } catch (RuntimeException e) {
            return new Outcome(false, "The merge failed: " + e.getMessage());
        }
    }

    /** {@code /discard <agent>} and {@code worktree_discard}. */
    public Outcome discard(String agent) {
        var found = manager.find(agent);
        if (found.isEmpty()) return new Outcome(false, "No worktree for '" + agent + "'.");
        try {
            return new Outcome(true, manager.discard(found.get()));
        } catch (RuntimeException e) {
            return new Outcome(false, "The discard failed: " + e.getMessage());
        }
    }

    /** {@code /worktrees prune}. */
    public Outcome prune() {
        List<Path> removed = manager.prune();
        return new Outcome(true, removed.isEmpty() ? "Nothing to prune."
            : "Removed " + removed.size() + " worktree(s): " + removed.stream().map(Path::toString)
                .reduce((a, b) -> a + ", " + b).orElse(""));
    }

    /** The result of an action. */
    public record Outcome(boolean ok, String message) {}
}
