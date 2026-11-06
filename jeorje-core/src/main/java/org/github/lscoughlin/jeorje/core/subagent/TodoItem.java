package org.github.lscoughlin.jeorje.core.subagent;

/**
 * One task of the todo list of an agent, as the turn watch sees it.
 *
 * @param text what to do
 * @param state {@code pending}, {@code in_progress}, or {@code completed}
 */
public record TodoItem(String text, String state) {
    /** True when the task is not done. */
    public boolean open() { return !"completed".equals(state); }
}
