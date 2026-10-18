package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.github.lscoughlin.jeorje.core.config.ConfigLoader;
import org.github.lscoughlin.jeorje.core.config.ConfigOptions;
import org.github.lscoughlin.jeorje.core.permissions.ToolPermissionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillsWiringTest {

    @TempDir
    Path tmp;
    Path home;
    Path proj;

    @BeforeEach
    void setUp() throws IOException {
        home = Files.createDirectories(tmp.resolve("home"));
        proj = Files.createDirectories(tmp.resolve("proj"));
        write(proj.resolve(".agents/skills/demo/SKILL.md"), "---\nname: demo\ndescription: Runs the demo.\n---\nbody");
        write(proj.resolve(".agents/skills/hidden/SKILL.md"),
                "---\nname: hidden\ndescription: Only for the user.\ndisable-model-invocation: true\n---\nbody");
        write(proj.resolve("AGENTS.md"), "PROJECT-RULE");
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private org.github.lscoughlin.jeorje.core.config.JeorjeConfig config(String yaml) throws IOException {
        if (!yaml.isEmpty()) write(home.resolve(".jeorje/config.yaml"), yaml);
        return ConfigLoader.load(ConfigOptions.of(home, proj)).config();
    }

    @Test
    void untrustedProjectHasNoProjectSkills() throws IOException {
        var config = config("");
        assertTrue(Wiring.skills(config, home, proj, false).skills().isEmpty());
        assertEquals(2, Wiring.skills(config, home, proj, true).skills().size());
    }

    @Test
    void skillsEnabledFalseTurnsSkillsAndCatalogOff() throws IOException {
        var config = config("skills:\n  enabled: false\n");
        var registry = Wiring.skills(config, home, proj, true);
        assertTrue(registry.skills().isEmpty());
        var text = Wiring.promptInstructions(config, proj, () -> registry.skills()).text(proj);
        assertTrue(text.contains("PROJECT-RULE"));
        assertFalse(text.contains("available_skills"));
    }

    @Test
    void compatDirectoriesAreExpandedAndScanned() throws IOException {
        write(home.resolve(".claude/skills/other/SKILL.md"), "---\nname: other\ndescription: From another tool.\n---\nb");
        var config = config("skills:\n  compat: [\"~/.claude/skills\"]\n");
        assertTrue(Wiring.skills(config, home, proj, false).find("other").isPresent());
    }

    @Test
    void catalogHidesHiddenSkillsAndRespectsDenyRules() throws IOException {
        var config = config("permissions:\n  rules:\n    - {tool: skill, decision: deny}\n");
        var registry = Wiring.skills(config, home, proj, true);
        var policy = ToolPermissionPolicy.builder(proj).configRules(config.permissions().rules()).build();
        var visible = Wiring.visibleSkills(registry, policy);
        var denied = Wiring.hiddenSkills(registry, policy);
        assertTrue(denied.contains("demo"), denied.toString());
        assertTrue(denied.contains("hidden"), denied.toString());
        assertEquals(List.of(), visible.stream().map(s -> s.name()).toList());
    }

    @Test
    void catalogListsVisibleSkillsWithTheirLocation() throws IOException {
        var config = config("");
        var registry = Wiring.skills(config, home, proj, true);
        var policy = ToolPermissionPolicy.builder(proj).build();
        var text = Wiring.promptInstructions(config, proj, () -> Wiring.visibleSkills(registry, policy)).text(proj);
        assertTrue(text.contains("<name>demo</name>"), text);
        assertTrue(text.contains(".agents/skills/demo/SKILL.md</location>"), text);
        assertFalse(text.contains("<name>hidden</name>"), text);
    }

    @Test
    void instructionsDisabledLeavesOnlyTheCatalog() throws IOException {
        var config = config("instructions:\n  enabled: false\n");
        var registry = Wiring.skills(config, home, proj, true);
        var text = Wiring.promptInstructions(config, proj, () -> registry.skills()).text(proj);
        assertFalse(text.contains("PROJECT-RULE"));
        assertTrue(text.contains("<name>demo</name>"));
    }
}
