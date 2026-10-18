package org.github.lscoughlin.jeorje.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiKeyAndScrubberTest {

    static final String KEY = "sk-or-v1-0123456789abcdef0123456789abcdef";

    @TempDir Path tmp;

    @Test
    void resolvesFromEnvInOrder() {
        var config = JeorjeConfig.defaults();
        var one = new ApiKeyResolver(Map.of("OPENROUTER_API_KEY", KEY));
        assertEquals("OPENROUTER_API_KEY", one.resolve(config).orElseThrow().envName());
        var blank = new ApiKeyResolver(Map.of("OPENROUTER_API_KEY", " "));
        assertTrue(blank.resolve(config).isEmpty());
        assertTrue(new ApiKeyResolver(Map.of()).resolve(config).isEmpty());
    }

    @Test
    void usesConfiguredVariableName() throws IOException {
        Files.createDirectories(tmp.resolve("h/.jeorje"));
        Files.writeString(tmp.resolve("h/.jeorje/config.yaml"), "llm:\n  apiKeyEnv: MY_KEY\n");
        var config = ConfigLoader.load(ConfigOptions.of(tmp.resolve("h"), tmp.resolve("p"))).config();
        assertEquals(List.of("MY_KEY"), config.llm().apiKeyEnv());
        var key = new ApiKeyResolver(Map.of("MY_KEY", KEY, "OPENROUTER_API_KEY", "other-other-other")).resolve(config);
        assertEquals(KEY, key.orElseThrow().value());
    }

    @Test
    void keyNeverAppearsInToStringOrLogMessages() throws IOException {
        var env = Map.of("OPENROUTER_API_KEY", KEY, "JEORJE_SWARM_TOKEN", "swarm-token-value-1");
        Files.createDirectories(tmp.resolve("p/.jeorje"));
        Files.writeString(tmp.resolve("p/.jeorje/config.yaml"), "swarm:\n  enabled: true\nllm:\n  baseUrl: x\n");
        var loaded = ConfigLoader.load(ConfigOptions.of(tmp.resolve("h"), tmp.resolve("p")).withEnv(env));
        var resolver = new ApiKeyResolver(env);
        var key = resolver.resolve(loaded.config()).orElseThrow();
        assertFalse(key.toString().contains(KEY));
        assertFalse(loaded.config().toString().contains(KEY));
        assertFalse(loaded.config().toString().contains("swarm-token-value-1"));
        assertFalse(String.join("\n", loaded.warnings()).contains(KEY));
        assertFalse(String.join("\n", loaded.warnings()).contains("swarm-token-value-1"));
        assertFalse(loaded.warnings().isEmpty());
    }

    @Test
    void scrubberRemovesKnownValuesAndPatterns() {
        var scrubber = new ApiKeyResolver(Map.of("OPENROUTER_API_KEY", "plain-secret-value", "JEORJE_SWARM_TOKEN", "tok-abcdefgh"))
                .scrubber(JeorjeConfig.defaults());
        assertEquals("a *** b", scrubber.scrub("a plain-secret-value b"));
        assertEquals("t=***", scrubber.scrub("t=tok-abcdefgh"));
        assertFalse(scrubber.scrub("key " + KEY + " end").contains(KEY));
        assertFalse(scrubber.scrub("Authorization: Bearer abcdefghijklmnop1234567890").contains("abcdefghijklmnop"));
        assertEquals("OPENROUTER_API_KEY=***", scrubber.scrub("OPENROUTER_API_KEY=hello12345"));
        assertEquals("\"password\": \"***\"", scrubber.scrub("\"password\": \"hunter2hunter2\""));
        assertFalse(scrubber.scrub("ghp_abcdefghijklmnopqrstuvwxyz0123").contains("ghp_abcdef"));
        assertFalse(scrubber.scrub("AKIAABCDEFGHIJKLMNOP").contains("AKIA"));
        assertEquals("plain text, no secrets", scrubber.scrub("plain text, no secrets"));
        assertEquals(null, scrubber.scrub(null));
    }

    @Test
    void shortSecretsAreNotRegistered() {
        var s = new SecretScrubber().register("ab");
        assertEquals("a ab b", s.scrub("a ab b"));
    }
}
