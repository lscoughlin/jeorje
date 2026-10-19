package org.github.lscoughlin.jeorje.core.plan;

import java.util.Objects;

/**
 * One step of a story.
 *
 * @param id the task id, for example {@code S1.T2}
 * @param text the description
 * @param state the checkbox state
 * @param line the 0-based line in the source file, or -1 for a task that was not parsed
 */
public record Task(String id, String text, TaskState state, int line) {
    public Task {
        Objects.requireNonNull(id, "id");
        text = text == null ? "" : text;
        state = state == null ? TaskState.OPEN : state;
    }

    public Task(String id, String text) { this(id, text, TaskState.OPEN, -1); }

    public Task withState(TaskState next) { return new Task(id, text, next, line); }

    /** The task as one line of a plan file. */
    public String render() {
        return "- [" + state.mark() + "] " + id + (text.isEmpty() ? "" : " " + text);
    }
}
