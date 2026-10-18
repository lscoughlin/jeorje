package org.github.lscoughlin.jeorje.core.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.github.lscoughlin.jeorje.core.instructions.Scopes;
import org.github.lscoughlin.jeorje.core.instructions.Skill;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SkillGrantOverTest {
    @Test
    void aViewOverAnotherBaseSharesTheGrantsOfASkillThatLoadsLater() {
        var asking = (org.github.lscoughlin.jeorje.core.port.PermissionPolicy) a -> Decision.ASK;
        var original = new SkillGrantPolicy(asking, true);
        var view = original.over(asking);
        var use = new ToolUse("u", "bash", JsonMapper.builder().build().readTree("{\"command\":\"git status\"}"));
        assertEquals(Decision.ASK, view.check(use));
        original.skillLoaded(new Skill("s", "d", Path.of("/x/SKILL.md"), Scopes.Scope.USER_AGENTS, "body",
                null, null, java.util.Map.of(), "Bash(git:*)", false));
        assertEquals(Decision.ALLOW, view.check(use));
    }
}
