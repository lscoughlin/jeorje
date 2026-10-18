package org.github.lscoughlin.jeorje.ui.screen;

/** The choice of the user for a permission prompt. */
public enum Decision {
    ALLOW_ONCE,
    ALLOW_SESSION,
    ALLOW_PROJECT,
    DENY;

    /** True if the tool call may run. */
    public boolean approved() { return this != DENY; }
}
