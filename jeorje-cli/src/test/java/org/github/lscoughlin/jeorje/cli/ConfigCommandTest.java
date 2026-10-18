package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConfigCommandTest {

    private static final String TOKEN = "super-secret-swarm-token-123";

    @Test
    void printsEffectiveConfigWithSourcesAndHidesSecrets() {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            home.write(".jeorje/config.yaml", "models:\n  default: vendor/user-model\n");
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of("JEORJE_SWARM_TOKEN", TOKEN), null);
            var code = cli.run("config", "--model", "vendor/flag-model", "--base-url", "https://example.org/v1");
            assertEquals(0, code, cli.err());
            var out = cli.out();
            assertTrue(out.contains("default: vendor/flag-model  # flag"), out);
            assertTrue(out.contains("baseUrl: \"https://example.org/v1\"  # flag"), out);
            assertTrue(out.contains("budgetPercent: 60  # default"), out);
            assertTrue(out.contains("token: \"***\"  # environment") || out.contains("token: ***  # environment"), out);
            assertFalse(out.contains(TOKEN));
        }
    }

    @Test
    void userConfigIsASourceWhenNoFlagOverridesIt() {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            home.write(".jeorje/config.yaml", "models:\n  default: vendor/user-model\n");
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), null);
            assertEquals(0, cli.run("config"), cli.err());
            assertTrue(cli.out().contains("default: vendor/user-model  # user config "), cli.out());
        }
    }

    @Test
    void flagSwarmTokenIsHiddenToo() {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), null);
            assertEquals(0, cli.run("--swarm-token", TOKEN, "config"), cli.err());
            assertFalse(cli.out().contains(TOKEN));
            assertTrue(cli.out().contains("# flag"));
        }
    }

    @Test
    void jsonFormatHoldsConfigAndSources() {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of("JEORJE_SWARM_TOKEN", TOKEN), null);
            assertEquals(0, cli.run("config", "--format", "json"), cli.err());
            var root = JsonMapper.builder().build().readTree(cli.out());
            assertEquals("~deepseek/deepseek-flash-latest", root.path("config").path("models").path("default").asString());
            assertEquals("default", root.path("sources").path("models.default").asString());
            assertEquals("environment", root.path("sources").path("swarm.token").asString());
            assertFalse(cli.out().contains(TOKEN));
        }
    }

    @Test
    void unknownFormatFails() {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), null);
            assertEquals(2, cli.run("config", "--format", "toml"));
            assertTrue(cli.err().contains("Unknown format"));
        }
    }

    @Test
    void keysShowDefaultsAndOverrides() {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            home.write(".jeorje/config.yaml", "ui:\n  keys:\n    agents: Ctrl-G\n");
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), null);
            assertEquals(0, cli.run("config", "--keys"), cli.err());
            assertTrue(cli.out().contains("ui.keys.send: Enter  # default"), cli.out());
            assertTrue(cli.out().contains("ui.keys.agents: Ctrl-G  # user config"), cli.out());
            assertTrue(cli.out().contains("editor.keys.save: Ctrl-S  # default"), cli.out());
        }
    }

    @Test
    void showPromptsPrintsBothPrompts() {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            cwd.write("AGENTS.md", "Always write tests.\n");
            home.write(".jeorje/config.yaml", "prompts:\n  system:\n    mode: append\n    text: Prefer small changes.\n");
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), null);
            assertEquals(0, cli.run("config", "--show-prompts", "--trust"), cli.err());
            var out = cli.out();
            assertTrue(out.contains("# System prompt"), out);
            assertTrue(out.contains("Prefer small changes."), out);
            assertTrue(out.contains("Always write tests."), out);
            assertTrue(out.contains("# Compaction prompt"), out);
        }
    }

    @Test
    void initWritesConfigOnceAndLoadsBack() {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), null);
            assertEquals(0, cli.run("config", "--init"), cli.err());
            assertTrue(cwd.exists(".jeorje/config.yaml"));
            assertTrue(cwd.read(".jeorje/config.yaml").contains("budgetPercent: 60"));
            assertEquals(0, cli.run("config"), cli.err());
            assertEquals(2, cli.run("config", "--init"));
            assertTrue(cli.err().contains("already exists"));
        }
    }

    @Test
    void noCommandPrintsNotAvailableMessage() {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), null);
            assertEquals(0, cli.run());
            assertTrue(cli.out().contains("needs a terminal"));
        }
    }

    @Test
    void invalidConfigExitsWithTwo() {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            home.write(".jeorje/config.yaml", "bogus: 1\n");
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), null);
            assertEquals(2, cli.run("config"));
            assertTrue(cli.err().contains("Unknown config key: bogus"), cli.err());
        }
    }

    @Test
    void showsTheContextWindowKeysWithTheirSources() {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            home.write(".jeorje/config.yaml", "context:\n  window: 64000\n  windows:\n    big/model: 900000\n");
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), null);
            assertEquals(0, cli.run("config"), cli.err());
            assertTrue(cli.out().contains("window: 64000  # user config "), cli.out());
            assertTrue(cli.out().contains("big/model: 900000"), cli.out());
        }
    }

    @Test
    void aBadContextWindowIsAnErrorWithTheKeyPath() {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            home.write(".jeorje/config.yaml", "context:\n  window: -1\n");
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), null);
            assertTrue(cli.run("config") != 0);
            assertTrue((cli.err() + cli.out()).contains("context.window"), cli.err());
        }
    }
}
