package org.github.lscoughlin.jeorje.core.implement;

import java.util.List;

/**
 * The persisted state of a run. The checkboxes in the plan file are the durable record of finished work. This
 * state adds the attempts, the workers, and the worktrees.
 *
 * @param status {@code running}, {@code paused}, {@code stopped}, {@code done}, or {@code blocked}
 */
public record RunState(String plan, String status, List<Entry> stories) {
    public RunState {
        stories = stories == null ? List.of() : List.copyOf(stories);
    }

    /** The state of one story. */
    public record Entry(String id, String state, int attempt, int failures, String worker, String branch,
                        String worktree, String result, String reason, List<String> conflicts, String note) {
        public Entry {
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }
    }

    public Entry entry(String id) {
        return stories.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
    }
}
