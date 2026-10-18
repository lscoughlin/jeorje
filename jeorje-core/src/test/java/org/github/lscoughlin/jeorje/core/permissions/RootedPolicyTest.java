package org.github.lscoughlin.jeorje.core.permissions;

import static org.github.lscoughlin.jeorje.core.permissions.PermissionTestSupport.path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The read-only skill folders and the policy of a subagent in a worktree. */
class RootedPolicyTest {
    @TempDir Path tmp;
    Path main;
    Path worktree;
    Path skills;
    Path extra;

    @BeforeEach
    void setUp() throws IOException {
        main = Files.createDirectories(tmp.resolve("main"));
        worktree = Files.createDirectories(main.resolve(".jeorje/worktrees/s/w1"));
        skills = Files.createDirectories(tmp.resolve("home/.agents/skills/demo"));
        extra = Files.createDirectories(tmp.resolve("extra"));
        Files.writeString(main.resolve("main.txt"), "main");
        Files.writeString(worktree.resolve("own.txt"), "own");
        Files.writeString(skills.resolve("SKILL.md"), "skill");
        Files.writeString(extra.resolve("e.txt"), "e");
    }

    private ToolPermissionPolicy.Builder builder() {
        return ToolPermissionPolicy.builder(main).readOnlyDirectories(List.of(tmp.resolve("home/.agents/skills")))
                .extraDirectories(List.of(extra)).flags(true, false);
    }

    @Test
    void skillFoldersAreReadableAndNeverWritable() {
        var policy = builder().build();
        assertEquals(Decision.ALLOW, policy.check(path("read", skills.resolve("SKILL.md").toString())));
        assertEquals(Decision.DENY, policy.check(path("write", skills.resolve("SKILL.md").toString())));
        assertEquals(Decision.DENY, policy.check(path("edit", skills.resolve("new.md").toString())));
        assertEquals(Decision.DENY, policy.check(path("read", tmp.resolve("home/other.txt").toString())));
    }

    @Test
    void aSubagentInAWorktreeCannotReachTheMainTree() {
        var policy = builder().build();
        var rooted = policy.rootedAt(new AgentId("sub-1"), worktree);
        var mainFile = main.resolve("main.txt").toString();
        assertEquals(Decision.ALLOW, policy.check(path("read", mainFile)));
        assertEquals(Decision.DENY, rooted.check(path("read", mainFile)));
        assertEquals(Decision.DENY, rooted.check(path("write", mainFile)));
        assertEquals(Decision.DENY, rooted.check(path("read", "../../../../main.txt")));
        assertEquals(Decision.ALLOW, rooted.check(path("read", worktree.resolve("own.txt").toString())));
        assertEquals(Decision.ALLOW, rooted.check(path("write", "own.txt")), "a relative path is relative to the worktree");
        assertEquals(Decision.ALLOW, rooted.check(path("write", "new.txt")));
    }

    @Test
    void theWorktreePolicyKeepsExtraAndReadOnlyDirectories() {
        var rooted = builder().build().rootedAt(new AgentId("sub-1"), worktree);
        assertEquals(Decision.ALLOW, rooted.check(path("write", extra.resolve("e.txt").toString())));
        assertEquals(Decision.ALLOW, rooted.check(path("read", skills.resolve("SKILL.md").toString())));
        assertEquals(Decision.DENY, rooted.check(path("write", skills.resolve("SKILL.md").toString())));
    }

    @Test
    void theWorktreePolicyIsNeverWiderThanTheParent() {
        var denying = ToolPermissionPolicy.builder(main).rules(List.of(new Rule("write", null, Decision.DENY)))
                .readOnlyDirectories(List.of(tmp.resolve("home/.agents/skills"))).build();
        var rooted = denying.rootedAt(new AgentId("sub-1"), worktree);
        assertEquals(Decision.DENY, rooted.check(path("write", "own.txt")));
        assertEquals(Decision.ASK, builder().flags(false, false).build().rootedAt(new AgentId("s"), worktree)
                .check(path("write", "own.txt")));
        assertEquals(denying.mode(), rooted.mode());
    }

    @Test
    void theWorktreePolicySharesTheSessionRulesOfTheParent() {
        var parent = builder().flags(false, false).build();
        var rooted = parent.rootedAt(new AgentId("sub-1"), worktree);
        assertEquals(Decision.ASK, rooted.check(path("write", "own.txt")));
        parent.addSessionRule(new Rule("write", null, Decision.ALLOW));
        assertEquals(Decision.ALLOW, rooted.check(path("write", "own.txt")));
    }

    @Test
    void yoloStaysOnForAWorktreePolicy() {
        var yolo = builder().flags(false, true).build();
        var rooted = yolo.rootedAt(new AgentId("sub-1"), worktree);
        assertEquals(ToolPermissionPolicy.Mode.YOLO, rooted.mode());
        assertEquals(Decision.ALLOW, rooted.check(path("read", main.resolve("main.txt").toString())));
    }

    @Test
    void theSensitiveJeorjeFolderOfTheWorktreeAsksButTheWorktreeItselfDoesNot() {
        var normal = builder().flags(false, false).build();
        var rooted = normal.rootedAt(new AgentId("sub-1"), worktree);
        assertEquals(Decision.ALLOW, rooted.check(path("read", "own.txt")));
        assertEquals(Decision.ASK, rooted.check(path("read", ".jeorje/config.yaml")));
        assertTrue(rooted.sandbox().workspace().endsWith("w1"));
    }
}
