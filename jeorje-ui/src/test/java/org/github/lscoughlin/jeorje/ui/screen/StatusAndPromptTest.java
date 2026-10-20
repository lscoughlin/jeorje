package org.github.lscoughlin.jeorje.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StatusAndPromptTest {
    @Test
    void statusLineShowsAllFields() {
        var s = new StatusLine().agent("main").state("working").model("m1").contextPercent(42).peers(2)
                .branch("feature/x").yolo(true).plan(true).implement(2, 5).notice("sub1 done");
        var t = s.text();
        for (var part : new String[] {"main [working]", "m1", "ctx 42%", "peers 2", "feature/x", "YOLO", "PLAN",
                "IMPLEMENT 2/5", "sub1 done"}) {
            assertTrue(t.contains(part), part + " in " + t);
        }
        assertEquals(20, s.render(20).columnLength());
        s.yolo(false).plan(false).clearImplement().clearNotices();
        var t2 = s.text();
        assertTrue(!t2.contains("YOLO") && !t2.contains("PLAN") && !t2.contains("IMPLEMENT"));
    }

    @Test
    void theStatusLineWarnsWhenTheContextIsAlmostFull() {
        var s = new StatusLine().agent("main").contextPercent(84);
        var normal = s.render(60).styleAt(0);
        assertEquals(84, s.contextPercent());
        s.contextPercent(StatusLine.WARN_PERCENT);
        var warn = s.render(60).styleAt(0);
        assertTrue(!normal.equals(warn), "the color changes at " + StatusLine.WARN_PERCENT + "%");
        s.contextPercent(-1);
        assertTrue(!s.text().contains("ctx"));
        assertEquals(normal, s.render(60).styleAt(0));
    }

    @Test
    void noticesKeepTheLastThree() {
        var s = new StatusLine();
        for (var i = 1; i <= 5; i++) s.notice("n" + i);
        var t = s.text();
        assertTrue(t.contains("n3 ; n4 ; n5") && !t.contains("n2"));
    }

    private static PermissionPrompt prompt() {
        var input = JsonMapper.builder().build().createObjectNode().put("command", "rm -rf build");
        return new PermissionPrompt(new AgentId("a"), new ToolUse("t1", "bash", input));
    }

    @Test
    void promptReturnsTheChosenDecision() {
        assertEquals(Optional.of(Decision.ALLOW_ONCE), prompt().handleKey("y"));
        assertEquals(Optional.of(Decision.ALLOW_SESSION), prompt().handleKey("s"));
        assertEquals(Optional.of(Decision.ALLOW_PROJECT), prompt().handleKey("p"));
        assertEquals(Optional.of(Decision.DENY), prompt().handleKey("n"));
        assertEquals(Optional.of(Decision.DENY), prompt().handleKey("esc"));
        assertEquals(Optional.empty(), prompt().handleKey("x"));
        var p = prompt();
        assertEquals(Optional.empty(), p.handleKey("down"));
        assertEquals(Optional.of(Decision.ALLOW_SESSION), p.handleKey("enter"));
    }

    @Test
    void promptBoxShowsActionAndChoices() {
        var lines = prompt().render(60);
        var all = String.join("\n", lines.stream().map(Object::toString).toList());
        assertTrue(all.contains("bash") && all.contains("rm -rf build"));
        assertTrue(all.contains("allow once") && all.contains("for the session")
                && all.contains("in the project") && all.contains("deny"));
        assertTrue(lines.stream().allMatch(l -> l.columnLength() == 60));
    }
}
