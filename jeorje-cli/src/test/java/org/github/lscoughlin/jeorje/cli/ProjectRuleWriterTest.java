package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.config.ApiKeyResolver;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.StubEmbedder;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import tools.jackson.databind.json.JsonMapper;

class ProjectRuleWriterTest {

    private static String read(TempWorkspace cwd) throws Exception {
        return Files.readString(ProjectRuleWriter.configFile(cwd.root()));
    }

    @Test
    void createsTheFileWhenItIsMissing() throws Exception {
        try (var cwd = new TempWorkspace()) {
            assertTrue(ProjectRuleWriter.allow(cwd.root(), "write"));
            assertEquals("permissions:\n  rules:\n    - tool: write\n      decision: allow\n", read(cwd));
            assertFalse(ProjectRuleWriter.allow(cwd.root(), "write"), "the same rule is not written twice");
        }
    }

    @Test
    void keepsOtherKeysAndCommentsWhenThereIsNoPermissionsKey() throws Exception {
        try (var cwd = new TempWorkspace()) {
            cwd.write(".jeorje/config.yaml", "# my project\nmodels:\n  default: a/b   # the model\n");
            ProjectRuleWriter.allow(cwd.root(), "edit");
            var text = read(cwd);
            assertTrue(text.startsWith("# my project\nmodels:\n  default: a/b   # the model\n"), text);
            assertTrue(text.contains("permissions:\n  rules:\n    - tool: edit\n      decision: allow"), text);
        }
    }

    @Test
    void addsToAnExistingListAndKeepsItsComments() throws Exception {
        try (var cwd = new TempWorkspace()) {
            cwd.write(".jeorje/config.yaml", """
                    permissions:
                      # rules of the project
                      rules:
                        - tool: bash
                          pattern: "mvn *"
                          decision: allow   # build
                      extraDirectories: []
                    models:
                      default: a/b
                    """);
            ProjectRuleWriter.allow(cwd.root(), "write");
            var text = read(cwd);
            assertTrue(text.contains("# rules of the project"), text);
            assertTrue(text.contains("decision: allow   # build"), text);
            assertTrue(text.contains("extraDirectories: []"), text);
            assertTrue(text.contains("models:\n  default: a/b"), text);
            var rules = rules(text);
            assertEquals(2, rules.size(), text);
        }
    }

    @Test
    void fillsAnEmptyRulesListAndAnEmptyPermissionsMapping() throws Exception {
        try (var cwd = new TempWorkspace()) {
            cwd.write(".jeorje/config.yaml", "permissions:\n  rules: []\n");
            ProjectRuleWriter.allow(cwd.root(), "write");
            assertEquals(1, rules(read(cwd)).size(), read(cwd));
        }
        try (var cwd = new TempWorkspace()) {
            cwd.write(".jeorje/config.yaml", "permissions: {}\nmodels:\n  default: a/b\n");
            ProjectRuleWriter.allow(cwd.root(), "write");
            assertEquals(1, rules(read(cwd)).size(), read(cwd));
            assertTrue(read(cwd).contains("default: a/b"));
        }
        try (var cwd = new TempWorkspace()) {
            cwd.write(".jeorje/config.yaml", "permissions:\n  extraDirectories: []\n");
            ProjectRuleWriter.allow(cwd.root(), "write");
            var text = read(cwd);
            assertEquals(1, rules(text).size(), text);
            assertTrue(text.contains("extraDirectories: []"), text);
        }
    }

    @Test
    void aFlowStyleListFallsBackToARewriteThatKeepsTheKeys() throws Exception {
        try (var cwd = new TempWorkspace()) {
            cwd.write(".jeorje/config.yaml",
                    "permissions: {rules: [{tool: bash, decision: ask}]}\nmodels: {default: a/b}\n");
            ProjectRuleWriter.allow(cwd.root(), "write");
            var text = read(cwd);
            assertEquals(2, rules(text).size(), text);
            assertTrue(text.contains("a/b"), text);
        }
    }

    /** The rule goes through the config loader and the policy of the next run. */
    @Test
    void theNextRunHonorsTheRule() throws Exception {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var env = new CliEnvironment(home.root(), cwd.root(), Map.of("OPENROUTER_API_KEY", "test-key"),
                    System.out, System.err, (config, model, key) -> new ScriptedLlmClient(),
                    config -> Optional.of(new StubEmbedder()), false);
            var options = CommandLine.populateCommand(new ContextWindowsTest.Holder(), "--dir", cwd.root().toString(),
                    "--trust").global;
            var use = new ToolUse("c1", "write", JsonMapper.builder().build().createObjectNode()
                    .put("path", "a.txt").put("content", "x"));
            var before = Wiring.loadConfig(options, env);
            var scrubber = new ApiKeyResolver(env.env()).scrubber(before.config());
            assertEquals(PermissionPolicy.Decision.ASK,
                    Wiring.policy(before.config(), options, env, cwd.root(), "s1", scrubber).check(use));

            ProjectRuleWriter.allow(cwd.root(), "write");

            var after = Wiring.loadConfig(options, env);
            assertTrue(after.config().permissions().rules().stream()
                    .anyMatch(r -> r.tool().equals("write") && r.decision().equals("allow")));
            assertEquals(PermissionPolicy.Decision.ALLOW,
                    Wiring.policy(after.config(), options, env, cwd.root(), "s2", scrubber).check(use));
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> rules(String text) {
        var tree = (Map<String, Object>) tools.jackson.dataformat.yaml.YAMLMapper.builder().build()
                .readValue(text, Object.class);
        return (java.util.List<Object>) ((Map<String, Object>) tree.get("permissions")).get("rules");
    }
}
