package org.github.lscoughlin.jeorje.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanModeTest {

    @Test
    void toolSetHasOnlyReadOnlyToolsAndPlanTools() {
        assertEquals(Set.of("read", "ls", "glob", "grep", "recall", "webfetch", "skill", "ask_user", "advisor",
                "todo", "plan_submit", "bash"), PlanMode.TOOLS);
        for (var blocked : new String[] {"write", "edit", "patch", "spawn_agent", "worktree_merge"}) {
            assertFalse(PlanMode.allows(blocked), blocked);
        }
        assertTrue(PlanMode.allows("plan_submit"));
    }

    @Test
    void bashIsInTheToolSet() {
        assertTrue(PlanMode.allows("bash"));
    }

    @Test
    void stateAndPrompt() {
        var mode = new PlanMode();
        assertFalse(mode.active());
        assertEquals("", mode.statusLabel());
        mode.enter("Add limits");
        assertTrue(mode.active());
        assertEquals("PLAN", mode.statusLabel());
        assertTrue(mode.promptSection().contains("Add limits"));
        assertTrue(mode.promptSection().contains("plan_submit"));
        assertTrue(mode.promptSection().contains("ask_user"));
        mode.enter("");
        assertEquals("Add limits", mode.goal());
        mode.done();
        assertFalse(mode.active());
        assertEquals("Add limits", mode.goal());
    }

    @Test
    void promptSectionIsInSystemPrompt() {
        var prompt = org.github.lscoughlin.jeorje.core.agent.SystemPromptBuilder.build(
                new org.github.lscoughlin.jeorje.core.agent.SystemPromptBuilder.Parts(null, java.util.List.of(),
                        Path.of("."), "test", java.time.LocalDate.of(2026, 1, 1), "", new PlanMode().promptSection()));
        assertTrue(prompt.contains("## Plan mode"));
        assertTrue(prompt.contains("You are in plan mode."));
    }

    @Test
    void cancelDeletesNewDraftAndRestoresOldPlan(@TempDir Path dir) throws IOException {
        var mode = new PlanMode();
        mode.enter("g");
        var file = dir.resolve("p.md");
        Files.writeString(file, "draft");
        mode.recordSubmission(file, null);
        assertEquals(file, mode.submittedPlan().orElseThrow());
        mode.cancel();
        assertFalse(Files.exists(file));
        assertFalse(mode.active());

        var older = dir.resolve("q.md");
        var prev = dir.resolve("q.prev.md");
        Files.writeString(older, "new draft");
        Files.writeString(prev, "old plan");
        mode.enter("h");
        mode.recordSubmission(older, prev);
        mode.cancel();
        assertEquals("old plan", Files.readString(older));
        assertFalse(Files.exists(prev));
    }
}
