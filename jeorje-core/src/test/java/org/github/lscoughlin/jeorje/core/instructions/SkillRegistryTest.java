package org.github.lscoughlin.jeorje.core.instructions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillRegistryTest {

    @TempDir
    Path tmp;

    private void skill(Path base, String folder, String frontmatter, String body) throws IOException {
        var dir = base.resolve(folder);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "---\n" + frontmatter + "\n---\n" + body);
    }

    private List<Scopes.Root> roots(Path home, Path project, boolean trusted, List<Path> extra, List<Path> compat) {
        return Scopes.roots("skills", home, project, trusted, extra, compat);
    }

    @Test
    void scanOrderGivesPrecedenceAndWarnsOnCollision() throws IOException {
        var home = tmp.resolve("home");
        var proj = tmp.resolve("proj");
        skill(proj.resolve(".jeorje/skills"), "same", "name: same\ndescription: project jeorje", "B");
        skill(proj.resolve(".agents/skills"), "same", "name: same\ndescription: project agents", "B");
        skill(home.resolve(".jeorje/skills"), "same", "name: same\ndescription: user jeorje", "B");
        skill(home.resolve(".agents/skills"), "same", "name: same\ndescription: user agents", "B");
        skill(home.resolve(".agents/skills"), "only-user", "name: only-user\ndescription: u", "B");
        var reg = SkillRegistry.scan(roots(home, proj, true, List.of(), List.of()));
        assertEquals("project jeorje", reg.find("same").orElseThrow().description());
        assertEquals(2, reg.skills().size());
        assertEquals(3, reg.diagnostics().stream().filter(d -> d.message().contains("has precedence")).count());
        assertTrue(reg.diagnostics().getFirst().message().contains(proj.resolve(".jeorje").toString()));
    }

    @Test
    void extraAndCompatDirsComeLast() throws IOException {
        var home = tmp.resolve("home");
        var extraBase = tmp.resolve("extra");
        var compat = tmp.resolve("compat");
        skill(extraBase.resolve("skills"), "x", "name: x\ndescription: from extra", "B");
        skill(compat, "x", "name: x\ndescription: from compat", "B");
        skill(compat, "c", "name: c\ndescription: compat only", "B");
        var reg = SkillRegistry.scan(roots(home, null, false, List.of(extraBase), List.of(compat)));
        assertEquals("from extra", reg.find("x").orElseThrow().description());
        assertTrue(reg.find("c").isPresent());
        assertEquals(Scopes.Scope.COMPAT, reg.find("c").orElseThrow().scope());
    }

    @Test
    void untrustedProjectItemsAreNotLoaded() throws IOException {
        var proj = tmp.resolve("proj");
        var home = tmp.resolve("home");
        skill(proj.resolve(".agents/skills"), "p", "name: p\ndescription: project", "B");
        skill(home.resolve(".agents/skills"), "u", "name: u\ndescription: user", "B");
        assertTrue(Scopes.hasProjectItems(proj));
        var closed = SkillRegistry.scan(roots(home, proj, false, List.of(), List.of()));
        assertEquals(List.of("u"), closed.skills().stream().map(Skill::name).toList());
        var open = SkillRegistry.scan(roots(home, proj, true, List.of(), List.of()));
        assertEquals(2, open.skills().size());
    }

    @Test
    void stopsInsideSkillFolderAndSkipsGitAndNodeModules() throws IOException {
        var base = tmp.resolve("skills");
        skill(base, "outer", "name: outer\ndescription: d", "B");
        skill(base.resolve("outer/assets"), "nested", "name: nested\ndescription: d", "B");
        skill(base.resolve(".git"), "g", "name: g\ndescription: d", "B");
        skill(base.resolve("node_modules"), "n", "name: n\ndescription: d", "B");
        skill(base.resolve("group"), "deep", "name: deep\ndescription: d", "B");
        Files.writeString(base.resolve("README.md"), "ignored");
        var reg = SkillRegistry.scan(List.of(new Scopes.Root(Scopes.Scope.EXTRA, base, false)));
        assertEquals(List.of("deep", "outer"), reg.skills().stream().map(Skill::name).toList());
    }

    @Test
    void depthAndDirectoryLimits() throws IOException {
        var base = tmp.resolve("skills");
        var deep = base;
        for (var i = 1; i <= 7; i++) {
            deep = deep.resolve("d" + i);
        }
        skill(deep.getParent(), "d7", "name: d7\ndescription: too deep", "B"); // depth 7
        skill(base.resolve("d1/d2/d3/d4/d5"), "d6", "name: d6\ndescription: ok", "B"); // depth 6
        var reg = SkillRegistry.scan(List.of(new Scopes.Root(Scopes.Scope.EXTRA, base, false)));
        assertTrue(reg.find("d6").isPresent());
        assertTrue(reg.find("d7").isEmpty());

        var many = tmp.resolve("many");
        for (var i = 0; i < 30; i++) {
            skill(many, "s" + i, "name: s" + i + "\ndescription: d", "B");
        }
        var limited = SkillRegistry.scan(List.of(new Scopes.Root(Scopes.Scope.EXTRA, many, false)), 6, 10);
        assertTrue(limited.skills().size() < 30);
        assertTrue(limited.diagnostics().stream().anyMatch(d -> d.message().contains("Scan stopped")));
    }

    @Test
    void missingDirectoriesAreNotAnError() {
        var reg = SkillRegistry.scan(roots(tmp.resolve("nohome"), tmp.resolve("noproj"), true, List.of(), List.of()));
        assertTrue(reg.skills().isEmpty());
        assertTrue(reg.diagnostics().isEmpty());
    }

    @Test
    void lenientNameHandling() throws IOException {
        var base = tmp.resolve("skills");
        skill(base, "folder", "name: Other_Name\ndescription: d", "B");
        skill(base, "long", "name: " + "a".repeat(70) + "\ndescription: d", "B");
        var reg = SkillRegistry.scan(List.of(new Scopes.Root(Scopes.Scope.EXTRA, base, false)));
        assertTrue(reg.find("Other_Name").isPresent());
        assertTrue(reg.find("a".repeat(70)).isPresent());
        assertTrue(reg.diagnostics().stream().allMatch(d -> d.level() == Diagnostic.Level.WARNING));
        assertTrue(reg.diagnostics().stream().anyMatch(d -> d.message().contains("does not match the folder")));
        assertTrue(reg.diagnostics().stream().anyMatch(d -> d.message().contains("longer than 64")));
    }

    @Test
    void missingDescriptionSkipsWithError() throws IOException {
        var base = tmp.resolve("skills");
        skill(base, "nodesc", "name: nodesc", "B");
        skill(base, "empty", "name: empty\ndescription: \"\"", "B");
        var reg = SkillRegistry.scan(List.of(new Scopes.Root(Scopes.Scope.EXTRA, base, false)));
        assertTrue(reg.skills().isEmpty());
        assertEquals(2, reg.diagnostics().stream().filter(d -> d.level() == Diagnostic.Level.ERROR).count());
    }

    @Test
    void colonRepairLoadsSkillAndStillBrokenYamlIsSkipped() throws IOException {
        var base = tmp.resolve("skills");
        skill(base, "colon", "name: colon\ndescription: Use when: the user says \"hi\"", "B");
        skill(base, "broken", "name: broken\ndescription: [unclosed\nother: : :", "B");
        var reg = SkillRegistry.scan(List.of(new Scopes.Root(Scopes.Scope.EXTRA, base, false)));
        assertEquals("Use when: the user says \"hi\"", reg.find("colon").orElseThrow().description());
        assertTrue(reg.find("broken").isEmpty());
        assertTrue(reg.diagnostics().stream().anyMatch(d -> d.level() == Diagnostic.Level.ERROR
                && d.message().contains("Invalid YAML")));
        assertTrue(reg.diagnostics().stream().anyMatch(d -> d.message().contains("quotes")));
    }

    @Test
    void largeBodyWarnsButLoads() throws IOException {
        var base = tmp.resolve("skills");
        skill(base, "big", "name: big\ndescription: d", "line\n".repeat(600));
        var reg = SkillRegistry.scan(List.of(new Scopes.Root(Scopes.Scope.EXTRA, base, false)));
        assertTrue(reg.find("big").isPresent());
        assertTrue(reg.diagnostics().stream().anyMatch(d -> d.message().contains("body is large")));
    }

    @Test
    void fieldsMetadataAndHiding() throws IOException {
        var base = tmp.resolve("skills");
        skill(base, "full", "name: full\ndescription: d\nlicense: MIT\ncompatibility: needs git\n"
                + "metadata:\n  author: me\nallowed-tools: Bash(git:*) Read Foo\ndisable-model-invocation: true", "Body");
        skill(base, "shown", "name: shown\ndescription: d", "Body");
        var reg = SkillRegistry.scan(List.of(new Scopes.Root(Scopes.Scope.EXTRA, base, false)));
        var full = reg.find("full").orElseThrow();
        assertEquals("MIT", full.license());
        assertEquals("me", full.metadata().get("author"));
        assertTrue(full.disableModelInvocation());
        assertEquals("Body", full.body());
        assertEquals(List.of("shown"), reg.modelVisible(Set.of()).stream().map(Skill::name).toList());
        assertTrue(reg.modelVisible(Set.of("shown")).isEmpty());
        assertTrue(reg.allowedToolsDiagnostics(AllowedTools.DEFAULT_TOOLS).getFirst().message().contains("Foo"));
        assertEquals(1, reg.builtInClashes(Set.of("full")).size());
    }

    @Test
    void catalogListsAndCutsAtMaxChars() throws IOException {
        var base = tmp.resolve("skills");
        for (var i = 0; i < 10; i++) {
            skill(base, "s" + i, "name: s" + i + "\ndescription: description number " + i, "B");
        }
        var reg = SkillRegistry.scan(List.of(new Scopes.Root(Scopes.Scope.EXTRA, base, false)));
        var full = SkillCatalog.build(reg.skills(), 8000);
        assertTrue(full.text().startsWith("The following skills give instructions"));
        assertTrue(full.text().contains("<name>s3</name>"));
        assertTrue(full.text().contains("<location>" + base.toAbsolutePath()));
        assertTrue(full.omitted().isEmpty());
        var cut = SkillCatalog.build(reg.skills(), 700);
        assertTrue(cut.text().length() <= 700);
        assertFalse(cut.omitted().isEmpty());
        assertEquals(10, cut.listed().size() + cut.omitted().size());
        assertEquals("", SkillCatalog.build(List.of(), 700).text());
    }

    @Test
    void allowedToolsParsing() {
        var parsed = AllowedTools.parse("Bash(git:*) Bash(jq:*) Read EDIT Write(src/**) Nope Bash(npm run test)");
        assertEquals(6, parsed.grants().size());
        assertEquals(new AllowedTools.Grant("bash", "git ", true), parsed.grants().get(0));
        assertEquals(new AllowedTools.Grant("read", null, false), parsed.grants().get(2));
        assertEquals(new AllowedTools.Grant("edit", null, false), parsed.grants().get(3));
        assertEquals(new AllowedTools.Grant("write", "src/**", false), parsed.grants().get(4));
        assertEquals(new AllowedTools.Grant("bash", "npm run test", false), parsed.grants().get(5));
        assertEquals(List.of("Nope"), parsed.ignored());
        assertTrue(AllowedTools.parse(null).grants().isEmpty());
    }
}
