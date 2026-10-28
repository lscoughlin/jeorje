package org.github.lscoughlin.jeorje.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.session.SessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The endpoint and the key come from the flags, then the environment, then the config files. */
class LlmEndpointConfigTest {
    private static final String FILE_KEY = "sk-file-0123456789abcdef0123456789abcdef";
    private static final String ENV_KEY = "sk-env-0123456789abcdef0123456789abcdef";
    private static final String FLAG_KEY = "sk-flag-0123456789abcdef0123456789abcdef";
    private static final String FILE_URL = "https://file.example.test/v1";
    private static final String ENV_URL = "https://env.example.test/v1";
    private static final String FLAG_URL = "https://flag.example.test/v1";

    @TempDir Path tmp;
    private Path home;
    private Path project;

    @BeforeEach
    void setUp() throws IOException {
        home = tmp.resolve("h");
        project = tmp.resolve("p");
        Files.createDirectories(home.resolve(".jeorje"));
        Files.createDirectories(project);
    }

    private void userConfig(String yaml) throws IOException {
        Files.writeString(home.resolve(".jeorje/config.yaml"), yaml);
    }

    private LoadedConfig load(Map<String, String> env, Map<String, Object> flags) {
        return ConfigLoader.load(ConfigOptions.of(home, project).withEnv(env).withFlags(flags));
    }

    @Test
    void theUserConfigFileSetsTheEndpointAndTheKey() throws IOException {
        userConfig("llm:\n  baseUrl: " + FILE_URL + "\n  apiKey: " + FILE_KEY + "\n");
        var loaded = load(Map.of(), Map.of());
        assertEquals(FILE_URL, loaded.config().llm().baseUrl());
        var key = new ApiKeyResolver(Map.of()).resolve(loaded.config()).orElseThrow();
        assertEquals(FILE_KEY, key.value());
        assertEquals(ConfigSource.Kind.USER, loaded.sourceOf("llm.apiKey").kind());
    }

    @Test
    void withoutAFileOrAKeyThereIsNoKey() {
        var loaded = load(Map.of(), Map.of());
        assertTrue(new ApiKeyResolver(Map.of()).resolve(loaded.config()).isEmpty());
        assertEquals("https://openrouter.ai/api/v1", loaded.config().llm().baseUrl());
    }

    @Test
    void theEnvironmentWinsOverTheConfigFile() throws IOException {
        userConfig("llm:\n  baseUrl: " + FILE_URL + "\n  apiKey: " + FILE_KEY + "\n");
        var env = Map.of("OPENROUTER_API_KEY", ENV_KEY, "JEORJE_BASE_URL", ENV_URL);
        var loaded = load(env, Map.of());
        assertEquals(ENV_URL, loaded.config().llm().baseUrl());
        assertEquals(ENV_KEY, new ApiKeyResolver(env).resolve(loaded.config()).orElseThrow().value());
        assertEquals(ConfigSource.Kind.ENV, loaded.sourceOf("llm.apiKey").kind());
    }

    @Test
    void theVariableNamedInTheConfigFileIsTheOneThatCounts() throws IOException {
        userConfig("llm:\n  apiKey: " + FILE_KEY + "\n  apiKeyEnv: [MY_KEY, OTHER_KEY]\n");
        var env = Map.of("OPENROUTER_API_KEY", "not-this-one-0123456789", "OTHER_KEY", ENV_KEY);
        var loaded = load(env, Map.of());
        assertEquals(ENV_KEY, loaded.config().llm().apiKey());
    }

    @Test
    void aBlankVariableDoesNotHideTheFileKey() throws IOException {
        userConfig("llm:\n  apiKey: " + FILE_KEY + "\n");
        var loaded = load(Map.of("OPENROUTER_API_KEY", "  "), Map.of());
        assertEquals(FILE_KEY, loaded.config().llm().apiKey());
    }

    @Test
    void theFlagsWinOverTheEnvironment() throws IOException {
        userConfig("llm:\n  baseUrl: " + FILE_URL + "\n  apiKey: " + FILE_KEY + "\n");
        var env = Map.of("OPENROUTER_API_KEY", ENV_KEY, "JEORJE_BASE_URL", ENV_URL);
        var loaded = load(env, Map.of("llm.apiKey", FLAG_KEY, "llm.baseUrl", FLAG_URL));
        assertEquals(FLAG_URL, loaded.config().llm().baseUrl());
        assertEquals(FLAG_KEY, new ApiKeyResolver(env).resolve(loaded.config()).orElseThrow().value());
        assertEquals(ConfigSource.Kind.FLAG, loaded.sourceOf("llm.apiKey").kind());
    }

    @Test
    void theKeyOfAnUntrustedProjectConfigIsIgnored() throws IOException {
        Files.createDirectories(project.resolve(".jeorje"));
        Files.writeString(project.resolve(".jeorje/config.yaml"), "llm:\n  apiKey: " + FILE_KEY + "\n");
        var loaded = load(Map.of(), Map.of());
        assertTrue(loaded.config().llm().apiKey().isEmpty());
        assertFalse(loaded.warnings().isEmpty());
        assertFalse(String.join("\n", loaded.warnings()).contains(FILE_KEY));
    }

    @Test
    void theKeyNeverShowsInTextOrInTheSessionFile() throws IOException {
        userConfig("llm:\n  apiKey: " + FILE_KEY + "\n");
        var loaded = load(Map.of(), Map.of());
        assertFalse(loaded.config().toString().contains(FILE_KEY));
        assertFalse(loaded.config().llm().toString().contains(FILE_KEY));
        assertEquals(SecretScrubber.MASK, ((Map<?, ?>) loaded.redactedTree().get("llm")).get("apiKey"));
        assertFalse(loaded.toYaml().contains(FILE_KEY));
        assertFalse(new ApiKeyResolver(Map.of()).scrubber(loaded.config()).scrub("key " + FILE_KEY).contains(FILE_KEY));

        var session = new SessionStore(project, Clock.systemUTC()).create(loaded.config());
        assertEquals("", session.manifest().config().path("llm").path("apiKey").asString());
        assertFalse(session.manifest().config().toString().contains(FILE_KEY));
    }
}
