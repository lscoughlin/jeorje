package org.github.lscoughlin.jeorje.core.instructions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandsAgentsTrustTest {

    @TempDir
    Path tmp;

    private void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void commandsNamesPlaceholdersAndBuiltIns() throws IOException {
        var proj = tmp.resolve("proj");
        var home = tmp.resolve("home");
        write(proj.resolve(".agents/commands/review.md"),
                "---\ndescription: Review\nargument-hint: \"[focus]\"\nmodel: vendor/strong\n---\nFocus on: $ARGUMENTS | $1 | $2");
        write(proj.resolve(".agents/commands/git/log.md"), "Plain $1");
        write(proj.resolve(".agents/commands/help.md"), "clash");
        write(home.resolve(".agents/commands/review.md"), "lower");
        var reg = CommandRegistry.scan(Scopes.roots("commands", home, proj, true, List.of(), List.of()), Set.of("help"));
        assertEquals(Set.of("review", "git:log"), reg.commands().stream().map(Command::name).collect(java.util.stream.Collectors.toSet()));
        var review = reg.find("review").orElseThrow();
        assertEquals("Review", review.description());
        assertEquals("[focus]", review.argumentHint());
        assertEquals("vendor/strong", review.model());
        assertEquals("Focus on: a \"b c\" | a | b c", review.render("a \"b c\""));
        assertEquals("Plain x", reg.find("git:log").orElseThrow().render("x y"));
        assertTrue(reg.find("help").isEmpty());
        assertTrue(reg.diagnostics().stream().anyMatch(d -> d.message().contains("built-in")));
        assertTrue(reg.diagnostics().stream().anyMatch(d -> d.message().contains("has precedence")));
    }

    @Test
    void untrustedProjectCommandsAreNotLoaded() throws IOException {
        var proj = tmp.resolve("proj");
        write(proj.resolve(".jeorje/commands/x.md"), "x");
        var reg = CommandRegistry.scan(Scopes.roots("commands", tmp.resolve("home"), proj, false, List.of(), List.of()), Set.of());
        assertTrue(reg.commands().isEmpty());
    }

    @Test
    void agentTypes() throws IOException {
        var proj = tmp.resolve("proj");
        write(proj.resolve(".agents/agents/reviewer.md"),
                "---\nname: reviewer\ndescription: Reviews code. Use after a change.\ntools: [read, grep]\nmodel: m/x\nisolation: worktree\n---\nYou review code.");
        write(proj.resolve(".agents/agents/nodesc.md"), "---\nname: nodesc\n---\nbody");
        write(proj.resolve(".agents/agents/plain.md"), "---\ndescription: d\nisolation: weird\n---\nbody");
        var reg = AgentTypeRegistry.scan(Scopes.roots("agents", tmp.resolve("home"), proj, true, List.of(), List.of()));
        var r = reg.find("reviewer").orElseThrow();
        assertEquals(List.of("read", "grep"), r.tools());
        assertEquals("worktree", r.isolation());
        assertEquals("m/x", r.model());
        assertEquals("You review code.", r.body());
        assertTrue(reg.find("nodesc").isEmpty());
        var plain = reg.find("plain").orElseThrow();
        assertNull(plain.tools());
        assertNull(plain.isolation());
        assertTrue(reg.diagnostics().stream().anyMatch(d -> d.level() == Diagnostic.Level.ERROR));
        assertTrue(reg.diagnostics().stream().anyMatch(d -> d.message().contains("isolation")));
    }

    @Test
    void agentTypesNeedTrustForProjectScope() throws IOException {
        var proj = tmp.resolve("proj");
        write(proj.resolve(".jeorje/agents/a.md"), "---\ndescription: d\n---\nb");
        var reg = AgentTypeRegistry.scan(Scopes.roots("agents", tmp.resolve("home"), proj, false, List.of(), List.of()));
        assertTrue(reg.types().isEmpty());
    }

    @Test
    void trustStoreByRealPathAndRevoke() throws IOException {
        var home = tmp.resolve("home");
        var proj = Files.createDirectories(tmp.resolve("proj"));
        var link = Files.createSymbolicLink(tmp.resolve("link"), proj);
        var store = new TrustStore(home);
        assertFalse(store.isTrusted(proj));
        store.trust(link);
        assertTrue(store.isTrusted(proj));
        assertTrue(Files.isRegularFile(home.resolve(".jeorje/trust.yaml")));
        assertTrue(new TrustStore(home).isTrusted(link));
        assertEquals(1, store.trustedProjects().size());
        assertEquals(proj.toRealPath().toString(), store.trustedProjects().getFirst());
        store.revoke(proj);
        assertFalse(store.isTrusted(proj));
    }

    @Test
    void worktreeInheritsTrustOfMainProject() throws IOException {
        var home = tmp.resolve("home");
        var main = Files.createDirectories(tmp.resolve("main"));
        var gitDir = Files.createDirectories(main.resolve(".git/worktrees/wt1"));
        var wt = Files.createDirectories(tmp.resolve("wt1"));
        Files.writeString(wt.resolve(".git"), "gitdir: " + gitDir + "\n");
        var store = new TrustStore(home);
        assertFalse(store.isTrusted(wt));
        store.trust(main);
        assertTrue(store.isTrusted(wt));
        assertEquals(main, TrustStore.mainProject(wt));
        store.revoke(wt);
        assertFalse(store.isTrusted(main));
    }

    @Test
    void yesAndYoloDoNotTrustButTrustFlagDoes() throws IOException {
        var proj = Files.createDirectories(tmp.resolve("proj"));
        var store = new TrustStore(tmp.resolve("home"));
        assertFalse(store.isTrustedForRun(proj, false, true, true));
        assertTrue(store.isTrustedForRun(proj, true, false, false));
        store.trust(proj);
        assertTrue(store.isTrustedForRun(proj, false, false, false));
    }
}
