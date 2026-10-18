package org.github.lscoughlin.jeorje.cli;

import java.util.Map;
import org.github.lscoughlin.jeorje.core.config.LoadedConfig;

/**
 * The context window of a model, in tokens. The config key {@code context.window} sets the window for all
 * models. The map {@code context.windows} (model slug to tokens) overrides it for single models. Without
 * both keys the window is {@value #DEFAULT} tokens. The config loader rejects a value that is not a positive whole number. The tree overload ignores such a value.
 */
public final class ContextWindows {
    /** The window that applies when the config sets none. */
    public static final int DEFAULT = 128_000;

    private ContextWindows() {}

    /** The window for a model, from the effective config values. */
    public static int resolve(LoadedConfig loaded, String model) {
        return loaded.config().context().windowFor(model);
    }

    /** The window for a model, from the typed context config. */
    public static int resolve(org.github.lscoughlin.jeorje.core.config.JeorjeConfig.Context context, String model) {
        return context.windowFor(model);
    }

    /** The window for a model, from a raw tree of config values. */
    public static int resolve(Map<String, Object> tree, String model) {
        if (!(tree.get("context") instanceof Map<?, ?> context)) return DEFAULT;
        if (model != null && context.get("windows") instanceof Map<?, ?> windows) {
            var override = positive(windows.get(model));
            if (override > 0) return override;
        }
        var all = positive(context.get("window"));
        return all > 0 ? all : DEFAULT;
    }

    private static int positive(Object value) {
        var number = switch (value) {
            case Number n -> n.longValue();
            case String s -> parse(s);
            case null, default -> 0L;
        };
        return number > 0 && number <= Integer.MAX_VALUE ? (int) number : 0;
    }

    private static long parse(String text) {
        try {
            return Long.parseLong(text.strip().replace("_", ""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
