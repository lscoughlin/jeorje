package org.github.lscoughlin.jeorje.cli.interactive;

import static org.github.lscoughlin.jeorje.cli.interactive.SessionRig.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The command {@code /resume}: the list of the sessions on disk, and the resume of one by id. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ResumeCommandTest {
    @TempDir Path tmp;

    private static void up(SessionRig rig) throws Exception {
        await("screen up", () -> rig.session.screen() != null && rig.session.slash() != null
                && rig.terminalText().contains("ready"));
    }

    @Test
    void aLongFirstPromptIsClippedToOneLine() throws Exception {
        var prompt = "please summarise " + "the walrus ".repeat(40);
        String id;
        try (var rig = new SessionRig(tmp, new SessionRig.Router(
                new ScriptedLlmClient(Response.text("ok"))), null)) {
            rig.initRepo();
            rig.start("--no-mouse");
            up(rig);
            rig.type(prompt + "\r");
            await("first turn", () -> rig.session.agent().turns().size() == 1);
            id = rig.session.session().id();
            rig.session.requestExit();
            assertEquals(0, rig.result.get(20, TimeUnit.SECONDS));
        }

        // A second process lists the closed session, so its transcript is complete.
        try (var rig = new SessionRig(tmp, new SessionRig.Router(new ScriptedLlmClient()), null)) {
            rig.start("--no-mouse");
            up(rig);
            var list = rig.session.slash().execute("/resume");
            assertFalse(list.error(), list.output());
            var line = list.output().lines().filter(l -> l.contains(id)).findFirst().orElseThrow();
            assertTrue(line.startsWith(id + " "), "the line starts with the id: " + line);
            assertTrue(line.endsWith("..."), "the long prompt is clipped: " + line);
            assertTrue(line.length() <= 120, "the line is short: " + line);
            assertTrue(line.contains("please summarise the walrus"), "the start of the prompt stays: " + line);

            rig.session.requestExit();
            assertEquals(0, rig.result.get(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void resumeListsTheSessionsNewestFirstAndResumesOneById() throws Exception {
        String older;
        try (var rig = new SessionRig(tmp, new SessionRig.Router(
                new ScriptedLlmClient(Response.text("Hello, walrus fan."))), null)) {
            rig.initRepo();
            rig.start("--no-mouse");
            up(rig);
            rig.type("hello walrus\r");
            await("first turn", () -> rig.session.agent().turns().size() == 1);
            older = rig.session.session().id();
            rig.session.requestExit();
            assertEquals(0, rig.result.get(20, TimeUnit.SECONDS));
        }

        // A second process starts a new conversation. The list holds both, and the older one comes back by id.
        try (var rig = new SessionRig(tmp, new SessionRig.Router(new ScriptedLlmClient()), null)) {
            rig.start("--no-mouse");
            up(rig);
            var current = rig.session.session().id();
            assertNotEquals(older, current, "the process starts a new conversation");

            var list = rig.session.slash().execute("/resume");
            assertFalse(list.error(), list.output());
            assertTrue(list.output().contains("Use /resume <id> to resume one."), list.output());
            var lines = list.output().lines().toList();
            assertTrue(lines.getFirst().contains(current), "the newest session is first: " + list.output());
            var olderLine = lines.stream().filter(l -> l.contains(older)).findFirst().orElseThrow();
            assertTrue(lines.indexOf(olderLine) > 0, "the older session is not first: " + list.output());
            assertTrue(olderLine.contains("hello walrus"), "the first user text is in the line: " + olderLine);
            assertTrue(olderLine.length() <= 120, "the line is short: " + olderLine);

            var resumed = rig.session.slash().execute("/resume " + older);
            assertFalse(resumed.error(), resumed.output());
            assertEquals("Resumed session " + older + ".", resumed.output());
            assertEquals(older, rig.session.session().id(), "the resumed session is the foreground conversation");

            // A missing id fails, and the foreground stays where it was.
            var foreground = rig.session.hub().foreground();
            var missing = rig.session.slash().execute("/resume no-such-session");
            assertTrue(missing.error(), missing.output());
            assertTrue(missing.output().contains("Session not found: no-such-session"), missing.output());
            assertEquals(foreground, rig.session.hub().foreground(), "the foreground did not move");
            assertEquals(older, rig.session.session().id(), "the conversation did not change");

            rig.session.requestExit();
            assertEquals(0, rig.result.get(20, TimeUnit.SECONDS));
        }
    }
}
