package org.github.lscoughlin.jeorje.core.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.instructions.Scopes;
import org.github.lscoughlin.jeorje.core.instructions.Skill;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SkillGrantPolicyTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ToolUse use(String tool, String key, String value) {
        return new ToolUse("id", tool, JSON.createObjectNode().put(key, value));
    }

    private static Skill skill(String allowed) {
        return new Skill("s", "d", Path.of("/x/s/SKILL.md"), Scopes.Scope.PROJECT_AGENTS, "b", null, null, Map.of(),
                allowed, false);
    }

    /** ASK for everything, DENY for rm. */
    private static final org.github.lscoughlin.jeorje.core.port.PermissionPolicy BASE = action -> {
        if (action.tool().equals("bash") && action.input().path("command").asString("").startsWith("rm ")) {
            return Decision.DENY;
        }
        return Decision.ASK;
    };

    @Test
    void nothingChangesBeforeASkillIsLoaded() {
        var policy = new SkillGrantPolicy(BASE, true);
        assertEquals(Decision.ASK, policy.check(use("bash", "command", "git status")));
    }

    @Test
    void bashPrefixAndToolNameMapToAllow() {
        var policy = new SkillGrantPolicy(BASE, true);
        policy.skillLoaded(skill("Bash(git:*) Read"));
        assertEquals(Decision.ALLOW, policy.check(use("bash", "command", "git status")));
        assertEquals(Decision.ASK, policy.check(use("bash", "command", "gitx status")));
        assertEquals(Decision.ASK, policy.check(use("bash", "command", "make")));
        assertEquals(Decision.ALLOW, policy.check(use("read", "path", "any/file")));
        assertEquals(Decision.ASK, policy.check(use("write", "path", "any/file")));
    }

    @Test
    void globPatternOnThePath() {
        var policy = new SkillGrantPolicy(BASE, true);
        policy.skillLoaded(skill("Edit(docs/*.md)"));
        assertEquals(Decision.ALLOW, policy.check(use("edit", "path", "docs/a.md")));
        assertEquals(Decision.ASK, policy.check(use("edit", "path", "src/a.md")));
    }

    @Test
    void neverAboveADeny() {
        var policy = new SkillGrantPolicy(BASE, true);
        policy.skillLoaded(skill("Bash"));
        assertEquals(Decision.DENY, policy.check(use("bash", "command", "rm -rf x")));
        assertEquals(Decision.ALLOW, policy.check(use("bash", "command", "ls")));
    }

    @Test
    void offByDefaultSwitch() {
        var policy = new SkillGrantPolicy(BASE, false);
        policy.skillLoaded(skill("Bash"));
        assertEquals(Decision.ASK, policy.check(use("bash", "command", "ls")));
    }
}
