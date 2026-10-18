package org.github.lscoughlin.jeorje.core.subagent;

/** The life cycle states of an agent in the subagent runtime. */
public enum AgentLifecycle {
    CREATED, RUNNING, WAITING_INPUT, WAITING_AGENT, SYNCING, RESOLVING, MERGING, COMPLETED, FAILED, CANCELLED;

    /** True for a state that an agent never leaves. */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /** True for the states of the completion sequence of a subagent with a worktree. */
    public boolean completionPhase() {
        return this == SYNCING || this == RESOLVING || this == MERGING;
    }

    /** True if an agent can move from this state to the other state. */
    public boolean canGo(AgentLifecycle to) {
        if (terminal() || to == this) return false;
        if (to == FAILED || to == CANCELLED) return true;
        return switch (this) {
            case CREATED -> to == RUNNING;
            case RUNNING -> to == WAITING_INPUT || to == WAITING_AGENT || to == SYNCING || to == COMPLETED;
            case WAITING_INPUT, WAITING_AGENT -> to == RUNNING || to == RESOLVING;
            case SYNCING -> to == RESOLVING || to == MERGING || to == COMPLETED;
            case RESOLVING -> to == SYNCING || to == WAITING_INPUT || to == WAITING_AGENT;
            case MERGING -> to == SYNCING || to == COMPLETED;
            default -> false;
        };
    }
}
