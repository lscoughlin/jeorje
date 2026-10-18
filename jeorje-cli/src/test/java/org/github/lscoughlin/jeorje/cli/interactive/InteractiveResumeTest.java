package org.github.lscoughlin.jeorje.cli.interactive;

import static org.github.lscoughlin.jeorje.cli.interactive.SessionRig.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.session.SessionStore;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 120, unit = TimeUnit.SECONDS)
class InteractiveResumeTest {
    @TempDir Path tmp;

    @Test
    void theInterfaceResumesTheLatestSessionAndTheModelContinues() throws Exception {
        String sessionId;
        try (var rig = new SessionRig(tmp, new SessionRig.Router(
                new ScriptedLlmClient(Response.text("Hello, walrus fan."))), null)) {
            rig.initRepo();
            rig.start("--no-mouse");
            await("screen up", () -> rig.session.screen() != null && rig.terminalText().contains("ready"));
            rig.type("hello walrus\r");
            await("first turn", () -> rig.session.agent().turns().size() == 1);
            sessionId = rig.session.session().id();
            rig.session.requestExit();
            assertEquals(0, rig.result.get(20, TimeUnit.SECONDS));
        }

        var second = new SessionRig.Router(new ScriptedLlmClient(Response.text("Welcome back.")));
        try (var rig = new SessionRig(tmp, second, null)) {
            rig.startResumed("", "--no-mouse");
            await("screen up", () -> rig.session.screen() != null && rig.terminalText().contains("ready"));
            assertEquals(sessionId, rig.session.session().id());
            assertEquals(1, rig.session.agent().turns().size(), "the earlier turn is back");
            await("resume notice", () -> rig.terminalText().contains("Resumed session"));
            rig.type("and now?\r");
            await("second turn", () -> rig.session.agent().turns().size() == 2);
            assertEquals(2, rig.session.agent().turns().getLast().number());
            var request = second.mainRequests.getFirst();
            assertTrue(request.messages().toString().contains("hello walrus"), "the model sees the earlier turn");
            assertTrue(request.messages().toString().contains("and now?"));
            rig.session.requestExit();
            assertEquals(0, rig.result.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, new SessionStore(tmp.resolve("repo")).list().size(), "the session was reused");
    }

    @Test
    void resumeOfAnUnknownSessionFailsBeforeTheScreenStarts() throws Exception {
        try (var rig = new SessionRig(tmp, new SessionRig.Router(new ScriptedLlmClient()), null)) {
            rig.initRepo();
            rig.startResumed("no-such-session", "--no-mouse");
            var error = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> rig.result.get(20, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("Session not found"), error.getCause().getMessage());
        }
    }
}
