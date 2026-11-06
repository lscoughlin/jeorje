package org.github.lscoughlin.jeorje.cli.interactive;

import static org.github.lscoughlin.jeorje.cli.interactive.SessionRig.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The orchestrator of each session, and the IMPLEMENT and turn fields of the status line. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ImplementStatusTest {
    @TempDir Path tmp;

    private static final Pattern TURN = Pattern.compile("turn (\\d+)/(\\d+)");

    private static Response[] blocked(int times) {
        var out = new Response[times * 2];
        for (var i = 0; i < times; i++) {
            out[2 * i] = Response.toolCall("b" + i, "story_blocked", "{\"reason\":\"cannot do it\"}");
            out[2 * i + 1] = Response.text("Blocked.");
        }
        return out;
    }

    private static SessionRig.Router router() {
        return new SessionRig.Router(new ScriptedLlmClient()).story("S1", blocked(6));
    }

    private static void start(SessionRig rig) throws Exception {
        rig.initRepo();
        Files.createDirectories(rig.repo.resolve("plan"));
        Files.writeString(rig.repo.resolve("plan/one.md"), "---\ntitle: One\nstatus: approved\ngoal: Make one file.\n"
                + "created: 2026-10-18\nupdated: 2026-10-18\nstories: 1\n---\n\n# Plan: One\n\n## Story S1: First file\n"
                + "- **Goal:** Goal of S1.\n- **Depends on:** none\n- **Touches:** `s1.txt`\n\n### Context\n"
                + "Context of S1.\n\n### Acceptance criteria\n- The file exists.\n\n### Verification\n"
                + "`test -f s1.txt`\n\n### Tasks\n- [ ] S1.T1 Create the file.\n");
        SessionRig.git(rig.repo, "add", "-A");
        SessionRig.git(rig.repo, "commit", "-q", "-m", "plan");
        rig.start();
        await("screen up", () -> rig.session.screen() != null && rig.session.slash() != null
                && rig.terminalText().contains("ready"));
    }

    private static String status(SessionRig rig) {
        return rig.session.screen().status().text();
    }

    @Test
    void eachSessionBuildsItsOwnOrchestratorOnTheFirstImplement() throws Exception {
        var router = router();
        try (var rig = new SessionRig(tmp, router, null)) {
            start(rig);
            var hub = rig.session.hub();
            assertNull(rig.session.orchestrator());
            assertFalse(rig.session.slash().execute("/implement one").error());
            var first = rig.session.orchestrator();
            assertNotNull(first);
            await("first run ended", () -> !first.active() && !first.rows().isEmpty());

            assertFalse(rig.session.slash().execute("/agents new build").error());
            assertNull(rig.session.orchestrator());
            assertFalse(rig.session.slash().execute("/implement one").error());
            var second = rig.session.orchestrator();
            assertNotNull(second);
            assertNotSame(first, second);
            await("second run ended", () -> !second.active() && !second.rows().isEmpty());
            assertNotSame(hub.get(new AgentId("main")).orchestrator(), hub.get(new AgentId("fork-1")).orchestrator());
        }
    }

    @Test
    void theImplementFieldShowsOnlyForTheForegroundSessionThatRuns() throws Exception {
        var router = router();
        try (var rig = new SessionRig(tmp, router, null)) {
            start(rig);
            assertFalse(rig.session.slash().execute("/implement one").error());
            await("counter", () -> status(rig).contains("IMPLEMENT [░░░░░░░░░░] 0/1"));
            assertFalse(rig.session.slash().execute("/agents new build").error());
            await("hidden in the other session", () -> !status(rig).contains("IMPLEMENT"));
            assertFalse(rig.session.slash().execute("/agents main").error());
            await("back in the first session", () -> status(rig).contains("IMPLEMENT"));
            await("run ended", () -> !rig.session.hub().get(new AgentId("main")).orchestrator().active());
        }
    }

    @Test
    void theTurnFieldFollowsTheForegroundRun() throws Exception {
        var router = router();
        var gate = new Semaphore(0);
        router.workerGate = gate;
        try (var rig = new SessionRig(tmp, router, null)) {
            start(rig);
            assertFalse(rig.session.slash().execute("/implement one").error());
            await("first turn", () -> {
                var m = TURN.matcher(status(rig));
                return m.find() && m.group(1).equals("1");
            });
            gate.release(1);
            await("second turn", () -> {
                var m = TURN.matcher(status(rig));
                return m.find() && Integer.parseInt(m.group(1)) >= 2;
            });
            assertFalse(rig.session.slash().execute("/agents new build").error());
            await("no turn in the other session", () -> !status(rig).contains("turn "));
            assertFalse(rig.session.slash().execute("/agents main").error());
            await("turn back", () -> TURN.matcher(status(rig)).find());
            gate.release(100);
            await("run ended", () -> !rig.session.orchestrator().active());
            await("turn cleared", () -> !status(rig).contains("turn "));
        }
    }
}
