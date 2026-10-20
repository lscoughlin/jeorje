package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.Notes;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.github.lscoughlin.jeorje.core.session.TurnChunker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmptyReplyTest {
    @TempDir Path dir;

    private Rig rig(Scripted llm) {
        return new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir));
    }

    private static String texts(org.github.lscoughlin.jeorje.core.model.LlmRequest request) {
        var sb = new StringBuilder();
        request.messages().forEach(m -> m.content().forEach(c -> {
            if (c instanceof Text t) sb.append(t.text()).append('\n');
        }));
        return sb.toString();
    }

    @Test
    void anEmptyReplyGetsOneNoteAndTheTurnContinues() {
        var llm = new Scripted(text(), text("Done."));
        var rig = rig(llm);
        rig.agent.runTurn("hello");
        assertEquals(2, llm.requests.size());
        assertTrue(texts(llm.requests.get(1)).contains(Notes.of(Agent.EMPTY_REPLY_NOTE)), texts(llm.requests.get(1)));
        assertTrue(rig.events(AgentEvent.Error.class).isEmpty());
        var last = rig.agent.turns().getFirst().messages().getLast();
        assertTrue(last.content().getFirst().toString().contains("Done."));
    }

    @Test
    void ABlankTextReplyCountsAsEmpty() {
        var llm = new Scripted(text("   \n"), text("Fine."));
        var rig = rig(llm);
        rig.agent.runTurn("hello");
        assertEquals(2, llm.requests.size());
    }

    @Test
    void threeEmptyRepliesEndWithAnError() {
        var llm = new Scripted(text(), text(), text(), text("never"));
        var rig = rig(llm);
        rig.agent.runTurn("hello");
        assertEquals(3, llm.requests.size(), "one call and two retries");
        var errors = rig.events(AgentEvent.Error.class);
        assertEquals(1, errors.size());
        assertEquals("The model gave an empty reply 3 times. The turn ends.", errors.getFirst().message());
        assertEquals(1, rig.events(AgentEvent.TurnFinished.class).size());
    }

    @Test
    void theCountStartsAgainAfterAGoodReply() {
        var llm = new Scripted(
            calls("c1", "ping", "{}"), text(), calls("c2", "ping", "{}"), text(), text("Done."));
        var ping = tool("ping", true, (in, ctx) -> org.github.lscoughlin.jeorje.core.model.ToolResultData.ok("pong"));
        var rig = new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir), ping);
        rig.agent.runTurn("go");
        assertEquals(5, llm.requests.size());
        assertTrue(rig.events(AgentEvent.Error.class).isEmpty());
    }

    @Test
    void theNoteJoinsTheLastUserMessageAfterToolResults() {
        var llm = new Scripted(calls("c1", "ping", "{}"), text(), text("Done."));
        var ping = tool("ping", true, (in, ctx) -> org.github.lscoughlin.jeorje.core.model.ToolResultData.ok("pong"));
        var rig = new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir), ping);
        rig.agent.runTurn("go");
        var messages = llm.requests.get(2).messages();
        var user = messages.get(messages.size() - 1);
        assertEquals(org.github.lscoughlin.jeorje.core.model.Role.USER, user.role());
        assertEquals(2, user.content().size(), "the tool result and the note");
        assertTrue(user.content().getLast() instanceof Text t && Notes.is(t.text()));
    }

    @Test
    void aNoteMakesNoUserChunk() {
        var llm = new Scripted(text(), text("Done."));
        var rig = rig(llm);
        rig.agent.runTurn("hello");
        for (var format : List.of(1, 2)) {
            var chunks = TurnChunker.forFormat("s1", format).chunk(rig.agent.turns().getFirst(), CLOCK.instant());
            var all = chunks.stream().map(c -> c.text()).reduce("", (a, b) -> a + "\n" + b);
            assertTrue(all.contains("NOTE Your last reply was empty."), all);
            assertFalse(all.contains("USER: " + Notes.PREFIX), all);
            assertTrue(chunks.stream().noneMatch(c -> c.kind().equals("user") && c.text().contains("empty")), format + "");
        }
    }
}
