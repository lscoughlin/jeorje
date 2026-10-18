package org.github.lscoughlin.jeorje.core.port;

import org.github.lscoughlin.jeorje.core.model.ToolUse;

/** Port that decides if a tool call can run. */
public interface PermissionPolicy {
    /** The decision for an action. */
    enum Decision { ALLOW, ASK, DENY }

    /** Checks a tool call before it runs. It never returns null. */
    Decision check(ToolUse action);
}
