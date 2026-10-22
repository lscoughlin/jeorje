package org.github.lscoughlin.jeorje.cli.interactive;

import static org.github.lscoughlin.jeorje.cli.interactive.SessionRig.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The command {@code /agents new}: two sessions in one process. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ForkSessionTest {
    @TempDir Path tmp;

    private static final AgentId MAIN = new AgentId("main");
    private static final AgentId FORK_1 = new AgentId("fork-1");
    private static final AgentId FORK_2 = new AgentId("fork-2");

    private static long sessionDirs(SessionRig rig) throws Exception {
        try (var stream = Files.list(rig.repo.resolve(".jeorje").resolve("sessions"))) {
            return stream.filter(Files::isDirectory).count();
        }
    }

    private static void up(SessionRig rig) throws Exception {
        rig.initRepo();
        rig.start();
        await("screen up", () -> rig.session.screen() != null && rig.session.slash() != null
                && rig.terminalText().contains("ready"));
    }

    @Test
    void agentsNewStartsANamedSessionInTheForegroundAndKeepsTheOldOneLive() throws Exception {
        var script = new ScriptedLlmClient(Response.text("one"), Response.text("two"), Response.text("three"),
                Response.text("four"));
        var router = new SessionRig.Router(script);
        try (var rig = new SessionRig(tmp, router, null)) {
            up(rig);
            assertEquals(1, sessionDirs(rig));
            var result = rig.session.slash().execute("/agents new build");
            assertFalse(result.error(), result.output());
            assertEquals(2, sessionDirs(rig));
            assertEquals(FORK_1, rig.session.screen().foreground());
            assertEquals(FORK_1, rig.session.hub().foreground());

            var entries = rig.session.roster().agents();
            var tops = entries.stream().filter(e -> rig.session.hub().get(e.id()) != null).toList();
            assertEquals(2, tops.size());
            assertEquals("main", tops.get(0).name());
            assertEquals("build", tops.get(1).name());
            assertEquals(MAIN, tops.get(1).parent());
            assertEquals("IDLE", tops.get(1).state());
            assertTrue(rig.session.slash().execute("/agents").output().contains("build"));

            // The old session is live. A message from the new session reaches its agent.
            var fork = rig.session.hub().get(FORK_1);
            var old = rig.session.hub().get(MAIN);
            assertNotEquals(old.session().id(), fork.session().id());
            var sent = fork.runtime().message(FORK_1, "build", "main", "ping from build");
            assertTrue(sent.ok(), () -> String.valueOf(sent.reason()));
            await("old agent got the message", () -> router.mainRequests.stream()
                    .anyMatch(r -> SessionRig.text(r.messages().getLast()).contains("ping from build")));
        }
    }

    @Test
    void agentsNewWithoutANameUsesTheSessionIdAndIdsCountUp() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            up(rig);
            assertFalse(rig.session.slash().execute("/agents new").error());
            assertFalse(rig.session.slash().execute("/agents new second").error());
            assertEquals(3, sessionDirs(rig));
            var hub = rig.session.hub();
            assertEquals(FORK_2, hub.foreground());
            assertEquals(hub.get(FORK_1).session().id(), hub.get(FORK_1).name());
            assertEquals("second", hub.get(FORK_2).name());
            assertEquals(FORK_1, hub.parent(FORK_2));
            assertEquals(3, rig.session.roster().agents().stream().filter(e -> hub.get(e.id()) != null).count());
        }
    }

    @Test
    void aDuplicateNameFailsAndChangesNothing() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            up(rig);
            assertFalse(rig.session.slash().execute("/agents new build").error());
            var again = rig.session.slash().execute("/agents new build");
            assertTrue(again.error());
            assertEquals(2, sessionDirs(rig));
            assertEquals(FORK_1, rig.session.hub().foreground());
        }
    }

    @Test
    void aPromptOfABackgroundSessionStaysPendingUntilTheUserSwitchesToIt() throws Exception {
        var script = new ScriptedLlmClient(
            Response.toolCall("b1", "bash", "{\"command\":\"mkdir -p target && touch target/touched\"}"), Response.text("done"),
            Response.text("spare"), Response.text("spare"));
        var router = new SessionRig.Router(script);
        try (var rig = new SessionRig(tmp, router, null)) {
            up(rig);
            assertFalse(rig.session.slash().execute("/agents new build").error());
            var hub = rig.session.hub();
            var old = hub.get(MAIN);
            // The background session runs a turn and raises a permission prompt.
            old.busy().set(true);
            old.agent().submit(new org.github.lscoughlin.jeorje.core.agent.AgentInput.UserInput("run it"));
            await("first request", () -> router.mainRequests.size() == 1);
            await("the prompt is queued", () -> old.agent().turns().size() >= 0 && old.busy().get());
            Thread.sleep(500);
            assertNull(rig.session.screen().pendingPrompt(), "the prompt of a background session is not shown");
            assertEquals(1, router.mainRequests.size());

            // The user switches to the background session. The prompt shows and the answer goes to its check.
            assertFalse(rig.session.slash().execute("/agents main").error());
            assertEquals(MAIN, hub.foreground());
            await("the prompt shows", () -> rig.session.screen().pendingPrompt() != null);
            assertEquals(MAIN, rig.session.screen().pendingPrompt().agent());
            rig.type("y");
            await("the turn continues", () -> router.mainRequests.size() == 2);
            await("the turn ends", () -> !old.busy().get());

            // Back in the new session, nothing came to it.
            assertFalse(rig.session.slash().execute("/agents build").error());
            assertEquals(FORK_1, hub.foreground());
            assertEquals(0, hub.get(FORK_1).agent().turns().size());
            assertNotNull(rig.session.screen().foreground());
            assertEquals(2, router.mainRequests.size());
        }
    }
}
