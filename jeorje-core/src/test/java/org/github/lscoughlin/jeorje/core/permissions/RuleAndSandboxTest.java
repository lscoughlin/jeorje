package org.github.lscoughlin.jeorje.core.permissions;

import static org.github.lscoughlin.jeorje.core.permissions.PermissionTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleAndSandboxTest {
    @TempDir Path tmp;
    Path ws;
    Path outside;

    @BeforeEach
    void setUp() throws IOException {
        ws = Files.createDirectories(tmp.resolve("ws"));
        outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(ws.resolve("a.txt"), "x");
        Files.writeString(outside.resolve("secret.txt"), "x");
    }

    ToolPermissionPolicy.Builder builder() {
        return ToolPermissionPolicy.builder(ws);
    }

    @Test
    void defaultsFollowTheTable() {
        var p = builder().build();
        assertEquals(Decision.ALLOW, p.check(path("read", "a.txt")));
        assertEquals(Decision.ALLOW, p.check(path("grep", ".")));
        assertEquals(Decision.ALLOW, p.check(use("recall", java.util.Map.of("query", "x"))));
        assertEquals(Decision.ASK, p.check(path("write", "a.txt")));
        assertEquals(Decision.ASK, p.check(path("edit", "a.txt")));
        assertEquals(Decision.ASK, p.check(path("patch", "a.txt")));
        assertEquals(Decision.ASK, p.check(bash("make")));
        assertEquals(Decision.ASK, p.check(use("worktree_merge", java.util.Map.of())));
        assertEquals(Decision.ASK, p.check(use("worktree_discard", java.util.Map.of())));
        for (var t : List.of("spawn_agent", "agent_message", "agent_ask", "agent_answer", "story_read",
                "skill", "todo", "ask_user", "advisor")) {
            assertEquals(Decision.ALLOW, p.check(use(t, java.util.Map.of())), t);
        }
    }

    @Test
    void moreSpecificRuleWins() {
        var p = builder().rules(List.of(
                new Rule("write", null, Decision.DENY),
                new Rule("write", "src/", Decision.ALLOW))).build();
        assertEquals(Decision.ALLOW, p.check(path("write", "src/Main.java")));
        assertEquals(Decision.DENY, p.check(path("write", "other.txt")));
    }

    @Test
    void tieGoesToTheStricterDecision() {
        var p = builder().rules(List.of(
                new Rule("write", null, Decision.ALLOW),
                new Rule("write", null, Decision.ASK),
                new Rule("edit", null, Decision.ALLOW),
                new Rule("edit", null, Decision.DENY))).build();
        assertEquals(Decision.ASK, p.check(path("write", "a.txt")));
        assertEquals(Decision.DENY, p.check(path("edit", "a.txt")));
        assertEquals(Decision.DENY, Rule.strictest(Decision.ASK, Decision.DENY));
    }

    @Test
    void configRulesAndSessionRulesApply() {
        var p = builder().configRules(List.of(new JeorjeConfig.PermissionRule("write", null, "allow"))).build();
        assertEquals(Decision.ALLOW, p.check(path("write", "a.txt")));
        var q = builder().build();
        q.addSessionRule(new Rule("bash", "make", Decision.ALLOW));
        assertEquals(Decision.ALLOW, q.check(bash("make test")));
    }

    @Test
    void regexAndGlobPatterns() {
        var p = builder().rules(List.of(
                new Rule("bash", "re:npm (test|run lint)", Decision.ALLOW),
                new Rule("write", "**/*.md", Decision.ALLOW))).build();
        assertEquals(Decision.ALLOW, p.check(bash("npm test")));
        assertEquals(Decision.ASK, p.check(bash("npm publish")));
        assertEquals(Decision.ALLOW, p.check(path("write", "docs/a.md")));
    }

    @Test
    void pathOutsideWorkspaceIsDeniedUnlessRuleAllows() {
        var p = builder().build();
        assertEquals(Decision.DENY, p.check(path("read", outside.resolve("secret.txt").toString())));
        assertEquals(Decision.DENY, p.check(path("read", "../outside/secret.txt")));
        var q = builder().rules(List.of(new Rule("read", outside.toString(), Decision.ALLOW))).build();
        assertEquals(Decision.ALLOW, q.check(path("read", outside.resolve("secret.txt").toString())));
    }

    @Test
    void symbolicLinkEscapeIsDenied() throws IOException {
        Files.createSymbolicLink(ws.resolve("link"), outside);
        Files.createSymbolicLink(ws.resolve("filelink"), outside.resolve("secret.txt"));
        var p = builder().build();
        assertEquals(Decision.DENY, p.check(path("read", "link/secret.txt")));
        assertEquals(Decision.DENY, p.check(path("read", "filelink")));
        assertEquals(Decision.DENY, p.check(path("write", "link/new.txt")));
        assertEquals(Decision.DENY, p.check(path("read", "link/../outside/secret.txt")));
    }

    @Test
    void danglingSymbolicLinkToOutsideIsDenied() throws IOException {
        Files.createSymbolicLink(ws.resolve("dangling"), outside.resolve("not-yet.txt"));
        assertEquals(Decision.DENY, builder().build().check(path("write", "dangling")));
    }

    @Test
    void symbolicLinkInsideWorkspaceIsFine() throws IOException {
        Files.createSymbolicLink(ws.resolve("inner"), ws.resolve("a.txt"));
        assertEquals(Decision.ALLOW, builder().build().check(path("read", "inner")));
    }

    @Test
    void dotDotInsideWorkspaceIsFine() throws IOException {
        Files.createDirectories(ws.resolve("sub"));
        assertEquals(Decision.ALLOW, builder().build().check(path("read", "sub/../a.txt")));
    }

    @Test
    void extraDirectoriesAreWorkspace() {
        var p = builder().extraDirectories(List.of(outside)).build();
        assertEquals(Decision.ALLOW, p.check(path("read", outside.resolve("secret.txt").toString())));
        assertEquals(Decision.ASK, p.check(path("write", outside.resolve("secret.txt").toString())));
    }

    @Test
    void skillFoldersAreReadOnly() {
        var p = builder().readOnlyDirectories(List.of(outside)).build();
        assertEquals(Decision.ALLOW, p.check(path("read", outside.resolve("secret.txt").toString())));
        assertEquals(Decision.DENY, p.check(path("write", outside.resolve("secret.txt").toString())));
        assertEquals(Decision.DENY, p.check(path("edit", outside.resolve("secret.txt").toString())));
    }

    @Test
    void gitAndConfigDirectoriesAlwaysAsk() throws IOException {
        Files.createDirectories(ws.resolve(".git"));
        var p = builder().rules(List.of(new Rule("write", null, Decision.ALLOW))).build();
        assertEquals(Decision.ASK, p.check(path("write", ".git/config")));
        assertEquals(Decision.ASK, p.check(path("write", ".jeorje/config.yaml")));
        assertEquals(Decision.ALLOW, p.check(path("write", "a.txt")));
        var denied = builder().rules(List.of(new Rule("write", ".git/", Decision.DENY))).build();
        assertEquals(Decision.DENY, denied.check(path("write", ".git/config")));
    }

    @Test
    void subagentIsNeverWiderThanParent() {
        var parent = builder().rules(List.of(new Rule("write", "locked", Decision.DENY))).build();
        var child = parent.narrow(new org.github.lscoughlin.jeorje.core.model.AgentId("c"),
                List.of(new Rule("write", null, Decision.ALLOW), new Rule("bash", "make", Decision.DENY)), false);
        assertEquals(Decision.DENY, child.check(path("write", "locked/x")));
        assertEquals(Decision.ALLOW, child.check(path("write", "free.txt")) == Decision.ASK
                ? Decision.ALLOW : child.check(path("write", "free.txt")));
        assertEquals(Decision.DENY, child.check(bash("make all")));
        var yoloParent = builder().flags(false, true).build();
        var yoloChild = yoloParent.narrow(new org.github.lscoughlin.jeorje.core.model.AgentId("c"),
                List.of(new Rule("write", null, Decision.DENY)), false);
        assertEquals(Decision.DENY, yoloChild.check(path("write", "a.txt")));
    }

    @Test
    void readOnlySubagentCannotWrite() {
        var parent = builder().flags(true, false).build();
        var child = parent.narrow(new org.github.lscoughlin.jeorje.core.model.AgentId("c"), List.of(), true);
        assertEquals(Decision.DENY, child.check(path("write", "a.txt")));
        assertEquals(Decision.ALLOW, child.check(path("read", "a.txt")));
    }

    @Test
    void mcpToolsAskByDefaultAndFollowPrefixRules() {
        var mcp = use("mcp__git__status", java.util.Map.of());
        assertEquals(Decision.ASK, builder().build().check(mcp));
        var allowed = builder().rules(List.of(new Rule("mcp__git__*", null, Decision.ALLOW))).build();
        assertEquals(Decision.ALLOW, allowed.check(mcp));
        assertEquals(Decision.ASK, allowed.check(use("mcp__other__status", java.util.Map.of())));
        var exact = builder().rules(List.of(new Rule("mcp__git__*", null, Decision.ALLOW),
                new Rule("mcp__git__push", null, Decision.DENY))).build();
        assertEquals(Decision.DENY, exact.check(use("mcp__git__push", java.util.Map.of())));
        assertEquals(Decision.ALLOW, exact.check(mcp));
    }

    @Test
    void mcpPrefixRuleBeatsAllowAll() {
        var p = builder().rules(List.of(new Rule("*", null, Decision.ALLOW),
                new Rule("mcp__git__*", null, Decision.DENY))).build();
        assertEquals(Decision.DENY, p.check(use("mcp__git__status", java.util.Map.of())));
        assertEquals(Decision.ALLOW, p.check(use("mcp__x__y", java.util.Map.of())));
    }

    @Test
    void mcpPathInputIsNotSandboxChecked() {
        var p = builder().build();
        assertEquals(Decision.ASK, p.check(use("mcp__fs__read", java.util.Map.of("path", outside.resolve("secret.txt").toString()))));
        assertEquals(Decision.ASK, p.check(use("mcp__fs__read", java.util.Map.of("path", "../../etc/passwd"))));
    }

    @Test
    void mcpToolsAreDeniedInPlanModeAndReadOnlyChildren() {
        var plans = ws.resolve("plans");
        var allowAll = new Rule("mcp__git__*", null, Decision.ALLOW);
        var planMode = builder().rules(List.of(allowAll)).planMode(true, plans).build();
        assertEquals(Decision.DENY, planMode.check(use("mcp__git__status", java.util.Map.of())));
        var parent = builder().rules(List.of(allowAll)).build();
        var child = parent.narrow(new org.github.lscoughlin.jeorje.core.model.AgentId("c"), List.of(), true);
        assertEquals(Decision.DENY, child.check(use("mcp__git__status", java.util.Map.of())));
        var wide = parent.narrow(new org.github.lscoughlin.jeorje.core.model.AgentId("c"),
                List.of(new Rule("mcp__git__*", null, Decision.ALLOW)), false);
        var strict = builder().rules(List.of(new Rule("mcp__git__*", null, Decision.DENY))).build()
                .narrow(new org.github.lscoughlin.jeorje.core.model.AgentId("c"),
                        List.of(new Rule("mcp__git__*", null, Decision.ALLOW)), false);
        assertEquals(Decision.ALLOW, wide.check(use("mcp__git__status", java.util.Map.of())));
        assertEquals(Decision.DENY, strict.check(use("mcp__git__status", java.util.Map.of())));
    }
}
