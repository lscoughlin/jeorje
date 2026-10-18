package org.github.lscoughlin.jeorje.core.instructions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.github.lscoughlin.jeorje.core.config.ConfigLoader;
import org.github.lscoughlin.jeorje.core.config.ConfigOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrustAtStartupTest {

    @TempDir
    Path tmp;

    @Test
    void storedTrustIsReadAtStartupAndLoadsTheFullProjectConfig() throws IOException {
        var home = Files.createDirectories(tmp.resolve("home"));
        var proj = Files.createDirectories(tmp.resolve("proj"));
        Files.createDirectories(proj.resolve(".jeorje"));
        Files.writeString(proj.resolve(".jeorje/config.yaml"), "skills:\n  maxListChars: 1234\n  honorAllowedTools: true\n");
        var before = ConfigLoader.load(ConfigOptions.of(home, proj));
        assertFalse(before.trusted());
        assertFalse(before.config().skills().honorAllowedTools());
        new TrustStore(home).trust(proj);
        var after = ConfigLoader.load(ConfigOptions.of(home, proj));
        assertTrue(after.trusted());
        assertTrue(after.config().skills().honorAllowedTools());
        assertEquals(1234, after.config().skills().maxListChars());
        new TrustStore(home).revoke(proj);
        assertFalse(ConfigLoader.load(ConfigOptions.of(home, proj)).trusted());
    }

    @Test
    void aWorktreeUsesTheTrustOfTheMainProject() throws IOException {
        var home = Files.createDirectories(tmp.resolve("home"));
        var main = Files.createDirectories(tmp.resolve("main"));
        var gitDir = Files.createDirectories(main.resolve(".git/worktrees/wt1"));
        var worktree = Files.createDirectories(tmp.resolve("wt1"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + gitDir + "\n");
        assertFalse(ConfigLoader.load(ConfigOptions.of(home, worktree)).trusted());
        new TrustStore(home).trust(main);
        assertTrue(ConfigLoader.load(ConfigOptions.of(home, worktree)).trusted());
        assertTrue(new TrustStore(home).isTrusted(worktree));
    }

    @Test
    void compatEntriesExpandHomeAndAreScanned() throws IOException {
        var home = Files.createDirectories(tmp.resolve("home"));
        var proj = Files.createDirectories(tmp.resolve("proj"));
        var userCfg = Files.createDirectories(home.resolve(".jeorje"));
        Files.writeString(userCfg.resolve("config.yaml"), "skills:\n  compat: [\"~/.claude/skills\"]\n");
        var skillDir = Files.createDirectories(home.resolve(".claude/skills/other"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: other\ndescription: From another tool.\n---\nbody");
        var loaded = ConfigLoader.load(ConfigOptions.of(home, proj));
        assertEquals(java.util.List.of(home + "/.claude/skills"), loaded.config().skills().compat());
        var compat = loaded.config().skills().compat().stream().map(Path::of).toList();
        var registry = SkillRegistry.scan(Scopes.roots("skills", home, proj, false, java.util.List.of(), compat));
        assertTrue(registry.find("other").isPresent());
    }
}
