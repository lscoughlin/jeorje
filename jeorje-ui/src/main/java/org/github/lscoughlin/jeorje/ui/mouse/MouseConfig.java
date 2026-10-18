package org.github.lscoughlin.jeorje.ui.mouse;

import java.util.Map;

/**
 * The mouse settings.
 *
 * @param enabled      key {@code ui.mouse.enabled}
 * @param wheelLines   key {@code ui.mouse.wheelLines}, lines for each wheel step
 * @param copyOnSelect key {@code ui.mouse.copyOnSelect}
 */
public record MouseConfig(boolean enabled, int wheelLines, boolean copyOnSelect) {
    public static final MouseConfig DEFAULT = new MouseConfig(true, 3, false);

    public MouseConfig {
        if (wheelLines < 1) throw new IllegalArgumentException("wheelLines must be at least 1");
    }

    /** Reads the keys {@code ui.mouse.enabled}, {@code ui.mouse.wheelLines}, and {@code ui.mouse.copyOnSelect}. */
    public static MouseConfig fromMap(Map<String, ?> values) {
        var enabled = values.get("ui.mouse.enabled") instanceof Boolean b ? b : DEFAULT.enabled();
        var wheel = values.get("ui.mouse.wheelLines") instanceof Number n ? n.intValue() : DEFAULT.wheelLines();
        var copy = values.get("ui.mouse.copyOnSelect") instanceof Boolean b ? b : DEFAULT.copyOnSelect();
        return new MouseConfig(enabled, Math.max(1, wheel), copy);
    }
}
