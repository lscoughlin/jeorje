package org.github.lscoughlin.jeorje.cli.interactive;

import static org.github.lscoughlin.jeorje.cli.interactive.SessionRig.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** {@code resume(id)}: an earlier conversation on disk becomes a live foreground session. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ResumeForegroundTest {
    @TempDir Path tmp;

    private static void up(SessionRig rig) throws Exception {
        await("screen up", () -> rig.session.screen() != null && rig.terminalText().contains("ready"));
    }

    @Test
    void resumeOpensTheEarlierConversationAndASecondCallSwitchesToIt() throws Exception {
        String sessionId;
        try (var rig = new SessionRig(tmp, new SessionRig.Router(
                new ScriptedLlmClient(Response.text("Hello, walrus fan."))), null)) {
            rig.initRepo();
            rig.start("--no-mouse");
            up(rig);
            rig.type("hello walrus\r");
            await("first turn", () -> rig.session.agent().turns().size() == 1);
            sessionId = rig.session.session().id();
            rig.session.requestExit();
            assertEquals(0, rig.result.get(20, TimeUnit.SECONDS));
        }

        // A new process starts a fresh conversation, then brings the earlier one into the foreground.
        try (var rig = new SessionRig(tmp, new SessionRig.Router(new ScriptedLlmClient()), null)) {
            rig.start("--no-mouse");
            up(rig);
            assertNotEquals(sessionId, rig.session.session().id(), "the process starts a new conversation");
            assertEquals(MainSession.MAIN, rig.session.hub().foreground());

            var id = rig.session.resume(sessionId);
            var ms = rig.session.hub().get(id);
            assertNotNull(ms, "the returned id names a live session");
            assertEquals(sessionId, ms.session().id(), "the live session holds the conversation of the id");
            assertEquals(id, rig.session.hub().foreground(), "the foreground moved to the resumed session");
            assertEquals(id, rig.session.screen().foreground());

            // The turns are back from the transcript, and the notice goes to the resumed chat.
            assertEquals(1, ms.agent().turns().size(), "the earlier turn is restored");
            assertTrue(rig.terminalText().contains("Resumed session " + sessionId + " with 1 turns"),
                    rig.terminalText());

            // A second resume of the same conversation switches instead of opening a duplicate.
            var live = rig.session.hub().sessions().size();
            assertEquals(id, rig.session.resume(sessionId), "the same session comes back");
            assertEquals(live, rig.session.hub().sessions().size(), "no duplicate session");

            // The list of conversations on disk holds both.
            assertEquals(2, rig.session.previousSessions().size());
            rig.session.requestExit();
            assertEquals(0, rig.result.get(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void resumeOfAnUnknownSessionFails() throws Exception {
        try (var rig = new SessionRig(tmp, new SessionRig.Router(new ScriptedLlmClient()), null)) {
            rig.initRepo();
            rig.start("--no-mouse");
            up(rig);
            var live = rig.session.hub().sessions().size();
            var error = assertThrows(IllegalArgumentException.class, () -> rig.session.resume("no-such-session"));
            assertTrue(error.getMessage().contains("Session not found: no-such-session"), error.getMessage());
            assertEquals(live, rig.session.hub().sessions().size(), "nothing was opened");
            assertEquals(MainSession.MAIN, rig.session.hub().foreground(), "the foreground did not move");
            rig.session.requestExit();
            assertEquals(0, rig.result.get(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void theResumedAgentCarriesTheEarlierContextIntoTheForeground() throws Exception {
        String sessionId;
        try (var rig = new SessionRig(tmp, new SessionRig.Router(
                new ScriptedLlmClient(Response.text("noted."))), null)) {
            rig.initRepo();
            rig.start("--no-mouse");
            up(rig);
            rig.type("remember the walrus\r");
            await("first turn", () -> rig.session.agent().turns().size() == 1);
            sessionId = rig.session.session().id();
            rig.session.requestExit();
            assertEquals(0, rig.result.get(20, TimeUnit.SECONDS));
        }

        var router = new SessionRig.Router(new ScriptedLlmClient(Response.text("I remember.")));
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.start("--no-mouse");
            up(rig);
            var id = rig.session.resume(sessionId);
            assertEquals(id, rig.session.hub().foreground());
            rig.type("what did I say?\r");
            await("a turn of the resumed session", () -> rig.session.hub().get(id).agent().turns().size() == 2);
            var request = router.mainRequests.getFirst();
            assertTrue(request.messages().toString().contains("remember the walrus"),
                    "the model sees the earlier turn");
            assertTrue(request.messages().toString().contains("what did I say?"));
            rig.session.requestExit();
            assertEquals(0, rig.result.get(20, TimeUnit.SECONDS));
        }
    }
}
