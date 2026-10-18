package org.github.lscoughlin.jeorje.core.subagent;

import java.util.Locale;

/** How the parent gets the result of a subagent. */
public enum SpawnMode {
    /** {@code spawn_agent} returns the agent id at once. The result comes later as an input event. */
    BACKGROUND,
    /** {@code spawn_agent} blocks the parent and returns the result. */
    WAIT;

    /** Parses {@code background} or {@code wait}. A null value gives {@link #BACKGROUND}. */
    public static SpawnMode parse(String value) {
        if (value == null || value.isBlank()) return BACKGROUND;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "background" -> BACKGROUND;
            case "wait" -> WAIT;
            default -> throw new SpawnException("Unknown mode '" + value + "'. Use background or wait.");
        };
    }
}
