package org.github.lscoughlin.jeorje.cli.interactive;

import static org.github.lscoughlin.jeorje.cli.interactive.SessionRig.await;
import static org.github.lscoughlin.jeorje.cli.interactive.SessionRig.git;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.implement.StoryState;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 120, unit = TimeUnit.SECONDS)
class InteractiveScenariosTest {
    @TempDir Path tmp;

    private static String planText(String status) {
        return "---\ntitle: One\nstatus: " + status + "\ngoal: Make one file.\ncreated: 2026-10-18\n"
                + "updated: 2026-10-18\nstories: 1\n---\n\n# Plan: One\n\n## Story S1: First file\n"
                + "- **Goal:** Goal of S1.\n- **Depends on:** none\n- **Touches:** `s1.txt`\n\n### Context\n"
                + "Context of S1.\n\n### Acceptance criteria\n- The file exists.\n\n### Verification\n"
                + "`test -f s1.txt`\n\n### Tasks\n- [ ] S1.T1 Create the file.\n";
    }

    private void commitPlan(SessionRig rig, String status) throws Exception {
        Files.createDirectories(rig.repo.resolve("plan"));
        Files.writeString(rig.repo.resolve("plan/one.md"), planText(status), StandardCharsets.UTF_8);
        git(rig.repo, "add", "-A");
        git(rig.repo, "commit", "-q", "-m", "plan");
    }

    @Test
    void workerPromptNamesTheStoryAndTheUserAnswersIt() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient())
                .story("S1",
                        Response.toolCall("w1", "write", "{\"path\":\"s1.txt\",\"content\":\"S1\"}"),
                        Response.toolCall("w2", "task_done", "{\"taskId\":\"S1.T1\",\"note\":\"ok\"}"),
                        Response.toolCall("w3", "story_done", "{\"summary\":\"DRAFT\",\"filesChanged\":[\"s1.txt\"]}"),
                        Response.text("Finished S1."));
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            commitPlan(rig, "approved");
            rig.start();
            await("screen up", () -> rig.session.screen() != null && rig.terminalText().contains("ready"));
            rig.type("/implement one\r");
            await("worker prompt", () -> rig.session.screen().pendingPrompt() != null);
            var prompt = rig.session.screen().pendingPrompt();
            assertNotNull(prompt);
            assertFalse(prompt.agent().value().equals("main"), "the prompt comes from a worker");
            assertEquals("write", prompt.toolUse().tool());
            assertTrue(rig.session.screen().status().text().contains("S1 asks to run write"),
                    rig.session.screen().status().text());
            assertTrue(rig.session.orchestrator().storyOf(prompt.agent()).orElse("").equals("S1"));
            rig.type("y");
            await("done", () -> {
                var rows = rig.session.orchestrator().rows();
                return rows.size() == 1 && rows.getFirst().state() == StoryState.DONE
                        && !rig.session.orchestrator().active();
            });
            assertEquals("S1", Files.readString(rig.repo.resolve("s1.txt")));
        }
    }

    @Test
    void aWorkerHasTheAdvisorToolByDefault() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient())
                .story("S1",
                        Response.toolCall("w1", "write", "{\"path\":\"s1.txt\",\"content\":\"S1\"}"),
                        Response.toolCall("w2", "task_done", "{\"taskId\":\"S1.T1\",\"note\":\"ok\"}"),
                        Response.toolCall("w3", "story_done", "{\"summary\":\"DRAFT\",\"filesChanged\":[\"s1.txt\"]}"),
                        Response.text("Finished S1."));
        // The advisor is on. The review of the story is off, so that no script is needed for the advisor model.
        try (var rig = new SessionRig(tmp, router, "implement:\n  review: none\n")) {
            rig.initRepo();
            commitPlan(rig, "approved");
            rig.start();
            await("screen up", () -> rig.session.screen() != null && rig.terminalText().contains("ready"));
            rig.type("/implement one\r");
            await("worker prompt", () -> rig.session.screen().pendingPrompt() != null);
            rig.type("y");
            await("done", () -> {
                var rows = rig.session.orchestrator().rows();
                return rows.size() == 1 && rows.getFirst().state() == StoryState.DONE
                        && !rig.session.orchestrator().active();
            });
            var requests = router.workers.get("S1").requests();
            assertFalse(requests.isEmpty());
            var names = requests.getFirst().tools().stream().map(t -> t.name()).toList();
            assertTrue(names.contains("advisor"), names.toString());
        }
    }

    @Test
    void ctrlCInterruptsAndTwiceExits() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start("--no-mouse");
            await("screen up", () -> rig.session.screen() != null && rig.terminalText().contains("ready"));
            assertEquals(Terminal.MouseTracking.Off, rig.terminal.getCurrentMouseTracking(), "--no-mouse");
            rig.type("\u0003");
            await("first notice", () -> rig.terminalText().contains("Press Ctrl-C again to exit"));
            assertFalse(rig.result.isDone());
            rig.type("\u0003");
            assertEquals(0, rig.result.get(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void trustPromptAppearsForProjectItemsAndTheAnswerTrustsTheProject() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            Files.createDirectories(rig.repo.resolve(".agents/commands"));
            Files.writeString(rig.repo.resolve(".agents/commands/hello.md"),
                    "---\ndescription: Say hello\n---\nSay hello to $ARGUMENTS\n");
            rig.start();
            await("trust prompt", () -> rig.session.screen() != null
                    && rig.session.screen().pendingPrompt() != null);
            assertEquals("trust_project", rig.session.screen().pendingPrompt().toolUse().tool());
            rig.type("y");
            await("trusted", () -> rig.session.slash() != null && rig.session.slash().execute("/trust").output()
                    .contains("already trusted"));
            assertTrue(rig.session.slash().execute("/commands").output().contains("hello"));
        }
    }

    @Test
    void trustFlagSkipsThePrompt() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            Files.createDirectories(rig.repo.resolve(".agents/commands"));
            Files.writeString(rig.repo.resolve(".agents/commands/hello.md"), "---\ndescription: Say hello\n---\nHi\n");
            rig.start("--trust");
            await("screen up", () -> rig.session.screen() != null && rig.terminalText().contains("ready"));
            Thread.sleep(200);
            assertEquals(null, rig.session.screen().pendingPrompt());
        }
    }

    @Test
    void exitListsTheWorktreesThatStayOnDisk() throws Exception {
        var blocked = new Response[] {
            Response.toolCall("b1", "story_blocked", "{\"reason\":\"cannot do it\"}"), Response.text("Blocked."),
            Response.toolCall("b2", "story_blocked", "{\"reason\":\"cannot do it\"}"), Response.text("Blocked.")};
        var router = new SessionRig.Router(new ScriptedLlmClient()).story("S1", blocked);
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            commitPlan(rig, "approved");
            rig.start();
            await("screen up", () -> rig.session.screen() != null && rig.terminalText().contains("ready"));
            rig.type("/implement one\r");
            await("blocked", () -> {
                var rows = rig.session.orchestrator().rows();
                return rows.size() == 1 && rows.getFirst().state() == StoryState.BLOCKED
                        && !rig.session.orchestrator().active();
            });
            rig.type("/worktrees\r");
            await("worktree list", () -> rig.session.worktrees() != null
                    && !rig.session.worktreeCommands().listText().startsWith("No worktrees"));
            rig.type("/quit\r");
            assertEquals(0, rig.result.get(20, TimeUnit.SECONDS));
            var out = new String(rig.stdout.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(out.contains("Kept worktrees:"), out);
            assertTrue(out.contains("jeorje/one/s1"), out);
        }
    }

    @Test
    void peersCommandSaysThatTheSwarmIsOff() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            await("screen up", () -> rig.session.screen() != null && rig.session.slash() != null
                    && rig.terminalText().contains("ready"));
            assertTrue(rig.session.slash().execute("/peers").output().contains("swarm is off"));
            assertTrue(rig.session.slash().execute("/files list").output().contains("README.md"));
            assertTrue(rig.session.slash().execute("/worktrees").output().contains("No worktrees"));
            assertTrue(rig.session.slash().execute("/implement status").output().contains("No run"));
            assertTrue(rig.session.slash().execute("/plan cancel").error());
            assertTrue(rig.session.slash().execute("/agents").output().contains("main"));
        }
    }
}
