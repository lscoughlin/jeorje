package org.github.lscoughlin.jeorje.core.config;

import java.nio.file.Path;
import java.util.Map;

/**
 * Input of the loader.
 *
 * @param home       the user home directory (the value of {@code ${HOME}})
 * @param cwd        the working directory (the value of {@code ${CWD}})
 * @param env        the environment variables
 * @param flags      flag values by dotted config key, for example {@code llm.baseUrl}
 * @param configFile the file given with {@code --config}, or null
 * @param trusted    true when the user trusts the project (for example {@code --trust})
 */
public record ConfigOptions(Path home, Path cwd, Map<String, String> env, Map<String, Object> flags,
                            Path configFile, boolean trusted) {

    public ConfigOptions {
        env = env == null ? Map.of() : Map.copyOf(env);
        flags = flags == null ? Map.of() : Map.copyOf(flags);
    }

    public static ConfigOptions of(Path home, Path cwd) {
        return new ConfigOptions(home, cwd, Map.of(), Map.of(), null, false);
    }

    public static ConfigOptions ofSystem() {
        return new ConfigOptions(Path.of(System.getProperty("user.home")),
                Path.of(System.getProperty("user.dir")), System.getenv(), Map.of(), null, false);
    }

    public ConfigOptions withEnv(Map<String, String> value) {
        return new ConfigOptions(home, cwd, value, flags, configFile, trusted);
    }

    public ConfigOptions withFlags(Map<String, Object> value) {
        return new ConfigOptions(home, cwd, env, value, configFile, trusted);
    }

    public ConfigOptions withConfigFile(Path value) {
        return new ConfigOptions(home, cwd, env, flags, value, trusted);
    }

    public ConfigOptions withTrusted(boolean value) {
        return new ConfigOptions(home, cwd, env, flags, configFile, value);
    }

    /** The user directory: {@code JEORJE_HOME}, or {@code ${HOME}/.jeorje}. */
    public Path userDir() {
        var override = env.get("JEORJE_HOME");
        return override == null || override.isBlank() ? home.resolve(".jeorje") : Path.of(override);
    }

    public Path projectDir() {
        return cwd.resolve(".jeorje");
    }
}
