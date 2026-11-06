package org.github.lscoughlin.jeorje.cli.interactive;

import static org.github.lscoughlin.jeorje.cli.interactive.SessionRig.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.form.Form;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The orchestrator as the foreground agent of its session during an {@code /implement} run. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class OrchestratorForegroundTest {
    @TempDir Path tmp;

    private static final AgentId MAIN = new AgentId("main");
    private static final AgentId FORK_1 = new AgentId("fork-1");

    private static Response[] blocked(int times) {
        var out = new Response[times * 2];
        for (var i = 0; i < times; i++) {
            out[2 * i] = Response.toolCall("b" + i, "story_blocked", "{\"reason\":\"cannot do it\"}");
            out[2 * i + 1] = Response.text("Blocked.");
        }
        return out;
    }

    private SessionRig.Router router() {
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

    private static AgentId foreground(SessionRig rig) {
        return rig.session.screen().foreground();
    }

    @Test
    void implementMakesTheOrchestratorTheForegroundAndTheRunEndsBackInMain() throws Exception {
        var router = router();
        var gate = new Semaphore(0);
        router.workerGate = gate;
        try (var rig = new SessionRig(tmp, router, null)) {
            start(rig);
            assertFalse(rig.session.slash().execute("/implement one").error());
            var orchestrator = rig.session.hub().get(MAIN).orchestrator();
            assertNotNull(orchestrator);
            await("orchestrator in the foreground", () -> foreground(rig).equals(orchestrator.agentId()));
            assertEquals(MAIN, rig.session.hub().foreground());
            assertSame(orchestrator, rig.session.orchestrator());

            var entries = rig.session.roster().agents();
            var entry = entries.stream().filter(e -> e.id().equals(orchestrator.agentId())).findFirst().orElseThrow();
            assertEquals("orchestrator", entry.name());
            assertEquals(MAIN, entry.parent());
            await("worker below the orchestrator", () -> rig.session.roster().agents().stream()
                    .anyMatch(e -> orchestrator.agentId().equals(e.parent())));

            gate.release(100);
            await("run ended", () -> !orchestrator.active() && !orchestrator.rows().isEmpty());
            await("back in main", () -> foreground(rig).equals(MAIN));
            assertTrue(rig.session.roster().agents().stream().anyMatch(e -> e.id().equals(orchestrator.agentId())));
            // The report shows in the chat of the main agent.
            assertTrue(rig.session.screen().chat(MAIN).logicalLines().stream()
                    .anyMatch(l -> l.toString().contains("IMPLEMENT")));
        }
    }

    @Test
    void textGoesToTheOrchestratorAndASwitchToAWorkerAndBackKeepsTheRun() throws Exception {
        var router = router();
        router.orchestrator = new ScriptedLlmClient(Response.text("I am here."));
        var gate = new Semaphore(0);
        router.workerGate = gate;
        try (var rig = new SessionRig(tmp, router, null)) {
            start(rig);
            assertFalse(rig.session.slash().execute("/implement one").error());
            var orchestrator = rig.session.hub().get(MAIN).orchestrator();
            await("orchestrator in the foreground", () -> foreground(rig).equals(orchestrator.agentId()));

            rig.type("hello orchestrator\r");
            await("orchestrator got the text", () -> router.orchestratorRequests.stream().anyMatch(r ->
                    SessionRig.text(r.messages().getLast()).contains("hello orchestrator")
                            || r.messages().stream().anyMatch(m -> SessionRig.text(m).contains("hello orchestrator"))));
            assertTrue(router.mainRequests.isEmpty());

            await("a worker", () -> rig.session.roster().agents().stream()
                    .anyMatch(e -> orchestrator.agentId().equals(e.parent())));
            var worker = rig.session.roster().agents().stream()
                    .filter(e -> orchestrator.agentId().equals(e.parent())).findFirst().orElseThrow();
            assertFalse(rig.session.slash().execute("/agents " + worker.id().value()).error());
            await("worker in the foreground", () -> foreground(rig).equals(worker.id()));
            assertEquals(MAIN, rig.session.hub().foreground());
            assertSame(orchestrator, rig.session.orchestrator());
            assertTrue(orchestrator.active());

            // Text for a worker in the foreground steers that worker. It does not reach the orchestrator.
            var orchestratorCalls = router.orchestratorRequests.size();
            rig.type("steer the worker\r");
            await("the text is typed", () -> rig.session.screen().chat(worker.id()).logicalLines().stream()
                    .anyMatch(l -> l.toString().contains("steer the worker")));
            // The first model call of the worker waits at the gate. The text reaches the next request.
            gate.release(2);
            await("the worker got the text", () -> router.workerRequests.stream().anyMatch(r ->
                    r.messages().stream().anyMatch(m -> SessionRig.text(m).contains("steer the worker"))));
            assertEquals(orchestratorCalls, router.orchestratorRequests.size());

            assertFalse(rig.session.slash().execute("/agents main").error());
            await("main", () -> foreground(rig).equals(MAIN));
            assertTrue(orchestrator.active());
            gate.release(100);
            await("run ended", () -> !orchestrator.active() && !orchestrator.rows().isEmpty());
            // The run ended while main was the foreground, so the foreground stays there.
            assertEquals(MAIN, foreground(rig));
        }
    }

    @Test
    void implementStatusAndStopWorkWhenTheForegroundIsTheOrchestratorOrAWorker() throws Exception {
        var router = router();
        var gate = new Semaphore(0);
        router.workerGate = gate;
        try (var rig = new SessionRig(tmp, router, null)) {
            start(rig);
            assertFalse(rig.session.slash().execute("/implement one").error());
            var orchestrator = rig.session.hub().get(MAIN).orchestrator();
            await("orchestrator in the foreground", () -> foreground(rig).equals(orchestrator.agentId()));
            var status = rig.session.slash().execute("/implement status");
            assertFalse(status.error(), status.output());
            assertTrue(status.output().contains("S1"), status.output());

            await("a worker", () -> rig.session.roster().agents().stream()
                    .anyMatch(e -> orchestrator.agentId().equals(e.parent())));
            var worker = rig.session.roster().agents().stream()
                    .filter(e -> orchestrator.agentId().equals(e.parent())).findFirst().orElseThrow();
            assertFalse(rig.session.slash().execute("/agents " + worker.id().value()).error());
            await("worker in the foreground", () -> foreground(rig).equals(worker.id()));
            var again = rig.session.slash().execute("/implement status");
            assertFalse(again.error(), again.output());
            assertTrue(again.output().contains("S1"), again.output());
            assertSame(orchestrator, rig.session.orchestrator());

            var stop = rig.session.slash().execute("/implement stop");
            assertFalse(stop.error(), stop.output());
            gate.release(100);
            await("run ended", () -> !orchestrator.active());
            // The orchestrator was not the foreground, so the worker stays in the foreground.
            assertEquals(worker.id(), foreground(rig));
        }
    }

    @Test
    void aSessionThatRunsInTheBackgroundDoesNotTakeTheForeground() throws Exception {
        var router = router();
        var gate = new Semaphore(0);
        router.workerGate = gate;
        try (var rig = new SessionRig(tmp, router, null)) {
            start(rig);
            assertFalse(rig.session.slash().execute("/implement one").error());
            var first = rig.session.hub().get(MAIN).orchestrator();
            await("first orchestrator in the foreground", () -> foreground(rig).equals(first.agentId()));

            assertFalse(rig.session.slash().execute("/agents new build").error());
            assertEquals(FORK_1, foreground(rig));
            assertEquals(FORK_1, rig.session.hub().foreground());
            assertNull(rig.session.orchestrator());

            gate.release(100);
            await("run of main ended", () -> !first.active() && !first.rows().isEmpty());
            // The orchestrator of main was not the foreground, so the run of main does not move the user.
            assertEquals(FORK_1, foreground(rig));
        }
    }

    @Test
    void theFormOfABackgroundOrchestratorWaitsUntilTheUserSwitchesToIt() throws Exception {
        var router = router();
        var gate = new Semaphore(0);
        router.workerGate = gate;
        try (var rig = new SessionRig(tmp, router, null)) {
            start(rig);
            assertFalse(rig.session.slash().execute("/implement one").error());
            var orchestrator = rig.session.hub().get(MAIN).orchestrator();
            await("orchestrator in the foreground", () -> foreground(rig).equals(orchestrator.agentId()));
            var screen = rig.session.screen();
            assertFalse(rig.session.slash().execute("/agents main").error());
            await("main", () -> foreground(rig).equals(MAIN));

            screen.showForm(new org.github.lscoughlin.jeorje.ui.screen.FormPrompt(orchestrator.agentId(),
                    Form.single("Which one?", java.util.List.of("a", "b"))), answer -> { });
            assertNull(screen.pendingForm());
            assertFalse(rig.session.slash().execute("/agents " + orchestrator.agentId().value()).error());
            await("orchestrator", () -> foreground(rig).equals(orchestrator.agentId()));
            assertNotNull(screen.pendingForm());
            screen.cancelForms(orchestrator.agentId());
            gate.release(100);
            await("run ended", () -> !orchestrator.active());
        }
    }

    private static void assertSame(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertSame(expected, actual);
    }
}
