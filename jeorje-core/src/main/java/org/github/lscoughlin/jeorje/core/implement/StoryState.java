package org.github.lscoughlin.jeorje.core.implement;

import java.util.Locale;

/** The state of a story during {@code /implement}. */
public enum StoryState {
    PENDING, RUNNING, DONE, BLOCKED;

    /** The lower-case name that the UI and {@code story_read} show. */
    public String label() { return name().toLowerCase(Locale.ROOT); }
}
