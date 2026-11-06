package org.github.lscoughlin.jeorje.core.subagent;

import java.util.List;

/**
 * Watches the model calls of one subagent and tells when the subagent needs help.
 *
 * <p>The subagent makes progress when its todo list changes between two calls: a task becomes done, or a task
 * is added, removed, reworded, or changes state. The watch counts the calls since the last progress. When the
 * count reaches {@link #HELP_THRESHOLD_PERCENT} percent of the limit, the watch asks once for help. A change
 * of the todo list resets the count and lets the watch ask again.
 *
 * <p>Entry points that the code reuses (no second path):
 * <ul>
 *   <li>The advisor: {@code AdvisorTool#advise} in {@code jeorje-tools}, reached through
 *       {@link AdvisorLink#advise}. The {@code advisor} tool of every agent uses the same method.</li>
 *   <li>The todos: {@code TodoTool#list} and {@code TodoTool#replace} in {@code jeorje-tools}, reached
 *       through {@link AdvisorLink#todos}. The {@code todo} tool of the subagent writes the same store.</li>
 * </ul>
 * The class is not thread safe. One agent thread uses it.
 */
public final class TurnWatch {
    /** The share of the limit at which the subagent asks for help. */
    public static final int HELP_THRESHOLD_PERCENT = 80;

    private final int limit;
    private List<TodoItem> last;
    private int turns;
    private boolean helpAsked;

    /** @param limit the turn limit of the subagent: the model calls that it may make without progress */
    public TurnWatch(int limit) { this.limit = limit; }

    /**
     * Counts one model call. A todo list that differs from the one of the last call is progress: the count goes
     * back to 0 and the help flag clears. The first call only sets the base list.
     *
     * @return true when this call showed progress
     */
    public boolean onTurn(List<TodoItem> todoSnapshot) {
        var snapshot = List.copyOf(todoSnapshot);
        if (last == null) {
            // The first look is the base of the comparison. It is not a change.
            last = snapshot;
            turns++;
            return false;
        }
        if (!snapshot.equals(last)) {
            last = snapshot;
            reset();
            return true;
        }
        turns++;
        return false;
    }

    /** The todo list changed from outside, for example by the advisor. The count goes back to 0. */
    public void onTodosChanged() { reset(); }

    /** Same as {@link #onTodosChanged()}, and the new list is the base for the next comparison. */
    public void onTodosChanged(List<TodoItem> snapshot) {
        last = List.copyOf(snapshot);
        reset();
    }

    /** True when the count reached the threshold and the watch did not ask in this window. */
    public boolean shouldAskForHelp() {
        return !helpAsked && limit > 0 && turns >= (long) limit * HELP_THRESHOLD_PERCENT / 100;
    }

    /** Records that the help request went out. */
    public void helpAsked() { helpAsked = true; }

    /** The model calls since the last progress. */
    public int turns() { return turns; }

    /** The turn limit. */
    public int limit() { return limit; }

    private void reset() {
        turns = 0;
        helpAsked = false;
    }
}
