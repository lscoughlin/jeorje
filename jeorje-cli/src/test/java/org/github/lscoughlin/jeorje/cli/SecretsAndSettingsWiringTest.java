package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.config.ApiKeyResolver;
import org.github.lscoughlin.jeorje.core.config.SecretScrubber;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.state.AgentState;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.StubEmbedder;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import tools.jackson.databind.json.JsonMapper;

/** The secret rules, the skill folders in the sandbox, and the {@code tools.*} settings in the real wiring. */
class SecretsAndSettingsWiringTest {
    private static final String KEY = "sk-or-v1-0123456789abcdef0123456789abcdef";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static CliEnvironment env(TempWorkspace home, TempWorkspace cwd) {
        return new CliEnvironment(home.root(), cwd.root(), Map.of("OPENROUTER_API_KEY", KEY), System.out, System.err,
                (config, model, key) -> new ScriptedLlmClient(), config -> Optional.of(new StubEmbedder()), false);
    }

    private static GlobalOptions options(TempWorkspace cwd, String... more) {
        var args = new java.util.ArrayList<>(java.util.List.of("--dir", cwd.root().toString(), "--trust"));
        args.addAll(java.util.List.of(more));
        return CommandLine.populateCommand(new ContextWindowsTest.Holder(), args.toArray(String[]::new)).global;
    }

    @Test
    void skillFoldersOutsideTheProjectAreReadOnlyInEveryPolicy() throws IOException {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            home.write(".agents/skills/demo/SKILL.md", "---\nname: demo\ndescription: d\n---\nbody");
            cwd.write(".jeorje/config.yaml", "skills:\n  dirs: [" + cwd.root().resolve("more") + "]\n");
            var env = env(home, cwd);
            var options = options(cwd);
            var loaded = Wiring.loadConfig(options, env);
            var folders = Wiring.skillFolders(loaded.config(), home.root(), cwd.root());
            assertTrue(folders.contains(home.root().resolve(".agents").resolve("skills")), folders.toString());
            assertTrue(folders.contains(cwd.root().resolve("more").resolve("skills")), folders.toString());
            var scrubber = new ApiKeyResolver(env.env()).scrubber(loaded.config());
            var policy = Wiring.policy(loaded.config(), options, env, cwd.root(), "s1", scrubber);
            var skillFile = home.root().resolve(".agents/skills/demo/SKILL.md").toString();
            assertEquals(PermissionPolicy.Decision.ALLOW, policy.check(use("read", "path", skillFile)));
            assertEquals(PermissionPolicy.Decision.DENY, policy.check(use("write", "path", skillFile)));
            assertEquals(PermissionPolicy.Decision.DENY,
                    policy.check(use("read", "path", home.root().resolve("other.txt").toString())));
            // The read-only folders pass to the policy of a subagent in a worktree.
            var rooted = policy.rootedAt(new org.github.lscoughlin.jeorje.core.model.AgentId("sub-1"),
                    cwd.root().resolve(".jeorje/worktrees/s/w"));
            assertEquals(PermissionPolicy.Decision.ALLOW, rooted.check(use("read", "path", skillFile)));
            assertEquals(PermissionPolicy.Decision.DENY, rooted.check(use("edit", "path", skillFile)));
        }
    }

    @Test
    void skillsOffMeansNoSkillFolders() throws IOException {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            cwd.write(".jeorje/config.yaml", "skills:\n  enabled: false\n");
            var loaded = Wiring.loadConfig(options(cwd), env(home, cwd));
            assertTrue(Wiring.skillFolders(loaded.config(), home.root(), cwd.root()).isEmpty());
        }
    }

    @Test
    void toolOutputThatEchoesTheKeyIsScrubbed() {
        var scrubber = new SecretScrubber().register(KEY);
        var dirty = ToolResultData.ok("1: " + KEY, java.util.List.of());
        var clean = Wiring.scrubbed(dirty, scrubber);
        assertEquals("1: ***", clean.output());
        assertFalse(clean.error());
        var error = Wiring.scrubbed(ToolResultData.error("failed with " + KEY), scrubber);
        assertTrue(error.error());
        assertFalse(error.output().contains(KEY));
        assertEquals(dirty, Wiring.scrubbed(dirty, null));
    }

    @Test
    void theRegistryWiresTheToolSettings() throws Exception {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            cwd.write(".jeorje/config.yaml", "tools:\n  diff: {context: 0}\n  patch: {fuzz: 0, conflicts: markers}\n"
                    + "  bash: {shell: /bin/sh, timeoutSeconds: 1}\n");
            cwd.write("a.txt", "1\n2\n3\n4\n5\n");
            var env = env(home, cwd);
            var loaded = Wiring.loadConfig(options(cwd), env);
            var policy = Wiring.policy(loaded.config(), options(cwd), env, cwd.root(), "s1", new SecretScrubber());
            var registry = Wiring.registry(loaded.config(), policy, policy.network(), new AgentState(), "s1");
            var context = new org.github.lscoughlin.jeorje.core.port.ToolContext(cwd.root(),
                    new org.github.lscoughlin.jeorje.core.model.AgentId("a"),
                    org.github.lscoughlin.jeorje.core.model.CancellationToken.none(), null, java.time.Clock.systemUTC());
            var diff = registry.execute(new ToolUse("1", "diff",
                    JSON.readTree("{\"a\":\"a.txt\",\"bContent\":\"1\\n2\\nX\\n4\\n5\\n\"}")), context);
            assertFalse(diff.error(), diff.output());
            assertFalse(diff.output().contains(" 2\n"), "context 0 from the config: " + diff.output());
            var bash = registry.execute(new ToolUse("2", "bash", JSON.readTree("{\"command\":\"sleep 20\"}")), context);
            assertTrue(bash.error());
            assertTrue(bash.output().contains("timed out"), bash.output());
        }
    }

    @Test
    void theLogLayoutRemovesKeys() {
        var context = new ch.qos.logback.classic.LoggerContext();
        var layout = new LoggingSetup.ScrubbingLayout(new SecretScrubber().register(KEY));
        layout.setContext(context);
        layout.setPattern("%msg%n");
        layout.start();
        var event = new ch.qos.logback.classic.spi.LoggingEvent(getClass().getName(),
                context.getLogger("t"), ch.qos.logback.classic.Level.INFO, "the key " + KEY + " leaked", null, null);
        var line = layout.doLayout(event);
        assertFalse(line.contains(KEY), line);
        assertTrue(line.contains("the key *** leaked"), line);
    }

    private static ToolUse use(String tool, String field, String value) {
        return new ToolUse("u", tool, JSON.createObjectNode().put(field, value));
    }
}
