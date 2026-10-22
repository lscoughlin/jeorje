package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Notes: a text that waits for the next turn and starts nothing. */
class AgentNoteTest {
    @TempDir Path dir;

    private Rig rig(Scripted llm) {
        return new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir));
    }

    private static List<String> userTexts(LlmRequest request) {
        var out = new ArrayList<String>();
        for (var message : request.messages()) {
            if (message.role() != Role.USER) continue;
            for (var c : message.content()) {
                if (c instanceof Text t) out.add(t.text());
            }
        }
        return out;
    }

    @Test
    void aNoteStartsNothing() {
        var llm = new Scripted(text("ok"));
        var rig = rig(llm);
        rig.agent.note("hello");
        assertTrue(rig.agent.turns().isEmpty());
        assertTrue(llm.requests.isEmpty());
    }

    @Test
    void aNoteReachesTheNextTurnBeforeTheUserText() {
        var llm = new Scripted(text("ok"));
        var rig = rig(llm);
        rig.agent.note("hello");
        rig.agent.runTurn("go");
        var texts = userTexts(llm.requests.getFirst());
        var note = texts.stream().filter(t -> t.contains("hello")).findFirst().orElseThrow();
        assertTrue(texts.indexOf(note) < texts.indexOf("go"), texts.toString());
    }

    @Test
    void notesKeepTheirOrder() {
        var llm = new Scripted(text("ok"));
        var rig = rig(llm);
        rig.agent.note("one");
        rig.agent.note("two");
        rig.agent.runTurn("go");
        var all = String.join("\n", userTexts(llm.requests.getFirst()));
        assertTrue(all.indexOf("one") >= 0 && all.indexOf("one") < all.indexOf("two"), all);
    }

    @Test
    void aNoteAddsNoTurnAndReachesOnlyOneTurn() {
        var llm = new Scripted(text("ok"), text("ok again"));
        var rig = rig(llm);
        rig.agent.note("hello");
        rig.agent.runTurn("go");
        assertEquals(1, rig.agent.turns().size());
        rig.agent.runTurn("again");
        assertEquals(2, rig.agent.turns().size());
        assertEquals(1, userTexts(llm.requests.getLast()).stream().filter(t -> t.contains("hello")).count());
    }

    @Test
    void aBlankNoteChangesNothing() {
        var llm = new Scripted(text("ok"));
        var rig = rig(llm);
        rig.agent.note("  ");
        rig.agent.note(null);
        rig.agent.runTurn("go");
        assertEquals(List.of("go"), userTexts(llm.requests.getFirst()));
    }
}
