package org.github.lscoughlin.jeorje.core.config;

import java.nio.file.Path;

/** Tells where one config value came from. Used by {@code jeorje config}. */
public record ConfigSource(Kind kind, Path file) {

    /** The layers, from the weakest to the strongest. */
    public enum Kind { DEFAULT, USER, PROJECT, CONFIG_FILE, ENV, FLAG }

    public static final ConfigSource DEFAULT = new ConfigSource(Kind.DEFAULT, null);

    public static ConfigSource of(Kind kind) {
        return new ConfigSource(kind, null);
    }

    public String describe() {
        var label = switch (kind) {
            case DEFAULT -> "default";
            case USER -> "user config";
            case PROJECT -> "project config";
            case CONFIG_FILE -> "--config file";
            case ENV -> "environment";
            case FLAG -> "flag";
        };
        return file == null ? label : label + " " + file;
    }
}
