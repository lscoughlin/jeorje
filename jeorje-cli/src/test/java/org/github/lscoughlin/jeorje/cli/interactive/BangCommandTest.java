package org.github.lscoughlin.jeorje.cli.interactive;

import static org.github.lscoughlin.jeorje.cli.interactive.SessionRig.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 120, unit = TimeUnit.SECONDS)
class BangCommandTest {
    @TempDir Path tmp;

    private static boolean ready(SessionRig rig) {
        return rig.session.screen() != null && rig.terminalText().contains("ready");
    }

    private static String chat(SessionRig rig) {
        return rig.session.screen().chat(InteractiveSession.MAIN).plainText();
    }

    private static String lastRequest(SessionRig rig) {
        var request = rig.router.mainRequests.getLast();
        var sb = new StringBuilder();
        for (var message : request.messages()) sb.append(SessionRig.text(message)).append('\n');
        return sb.toString();
    }

    @Test
    void bangLineShowsABlockAndReachesTheNextTurn() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient(Response.text("Seen.")));
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            await("screen up", () -> ready(rig));
            rig.type("! echo hello\r");
            await("block", () -> chat(rig).contains("[exit code 0]"));
            var lines = chat(rig).lines().toList();
            assertTrue(lines.contains("! echo hello"), lines.toString());
            assertTrue(lines.contains("hello"), lines.toString());
            assertTrue(rig.session.agent().turns().isEmpty());
            assertTrue(router.mainRequests.isEmpty());
            rig.type("go\r");
            await("request", () -> !router.mainRequests.isEmpty());
            var request = lastRequest(rig);
            assertTrue(request.contains("Shell command (run by the user, not by you): echo hello"), request);
            assertTrue(request.contains("Output:\nhello"), request);
            assertTrue(request.indexOf("hello") < request.lastIndexOf("go"), request);
        }
    }

    @Test
    void aFailingCommandShowsItsExitCode() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            await("screen up", () -> ready(rig));
            rig.type("! exit 3\r");
            await("block", () -> chat(rig).contains("[exit code 3]"));
            assertTrue(chat(rig).contains("(no output)"));
        }
    }

    @Test
    void aLoneBangShowsTheUsage() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            await("screen up", () -> ready(rig));
            rig.type("!\r");
            await("usage", () -> chat(rig).contains("Usage: !<command>"));
            assertFalse(chat(rig).contains("[exit code"));
        }
    }

    @Test
    void theHomeDirectoryBecomesATilde() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient(Response.text("Seen.")));
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            await("screen up", () -> ready(rig));
            rig.type("! echo $HOME\r");
            await("block", () -> chat(rig).contains("[exit code 0]"));
            rig.type("go\r");
            await("request", () -> !router.mainRequests.isEmpty());
            var request = lastRequest(rig);
            var home = System.getenv("HOME");
            assertTrue(request.contains("Output:\n~"), request);
            if (home != null && home.length() > 1) assertFalse(request.contains(home), request);
            assertEquals(1, rig.session.agent().turns().size());
        }
    }
}
