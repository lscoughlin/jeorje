package org.github.lscoughlin.jeorje.cli.interactive;

import static org.github.lscoughlin.jeorje.cli.interactive.SessionRig.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The wiring of the panes, the editor, the trust flow, the worktree cleanup, and the status line markers. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class InteractiveWiringTest {
    private static final String CTRL_X_A = "\u0018a";
    private static final String CTRL_X_F = "\u0018f";
    private static final String ALT_X = "\u001bx";
    private static final String DOWN = "\u001b[B";

    @TempDir Path tmp;

    private static void ready(SessionRig rig) throws InterruptedException {
        await("screen up", () -> rig.session.screen() != null && rig.session.slash() != null
                && rig.terminalText().contains("ready"));
    }

    @Test
    void theBrowserOpensAFileInTheEditorAndTheChatReturnsWithMouseTracking() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            ready(rig);
            await("mouse tracking", () -> rig.terminal.getCurrentMouseTracking() != Terminal.MouseTracking.Off);
            rig.type(CTRL_X_F);
            await("browser pane", () -> rig.session.screen().paneActive());
            rig.type(DOWN + "\r");
            await("editor", () -> rig.session.activeEditor() != null);
            assertEquals(rig.repo.resolve("README.md").toRealPath(), rig.session.activeEditor().path().toRealPath());
            assertFalse(rig.session.screen().paneActive());
            rig.type(ALT_X);
            await("chat back", () -> rig.session.activeEditor() == null);
            await("tracking on again", () -> rig.terminal.getCurrentMouseTracking() != Terminal.MouseTracking.Off);
            assertFalse(rig.session.screen().paneActive());
        }
    }

    @Test
    void editCommandOpensTheEditorAndSavesReachTheFileStateTable() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            ready(rig);
            rig.type("/edit README.md\r");
            await("editor", () -> rig.session.activeEditor() != null);
            rig.type("X\u0013");
            await("saved", () -> {
                try {
                    return Files.readString(rig.repo.resolve("README.md")).startsWith("X");
                } catch (java.io.IOException e) {
                    return false;
                }
            });
            var readme = rig.repo.resolve("README.md").toAbsolutePath().normalize();
            var real = readme.toRealPath();
            await("file state", () -> rig.session.toolState().files().get(readme).isPresent()
                    || rig.session.toolState().files().get(real).isPresent());
            var editor = rig.session.activeEditor();
            Files.writeString(rig.repo.resolve("README.md"), "changed by agent\n");
            editor.externalChange(rig.repo.resolve("README.md"));
            assertTrue(editor.diskChanged());
            rig.type(ALT_X);
            await("closed", () -> rig.session.activeEditor() == null);
        }
    }

    private static String chatText(SessionRig rig, AgentId id) {
        return String.join("\n", rig.session.screen().chat(id).logicalLines().stream().map(Object::toString).toList());
    }

    @Test
    void aFinishedWorktreeSubagentIsCleanedUpAndTheSwitcherShowsItsHistory() throws Exception {
        var main = new ScriptedLlmClient(
                Response.toolCall("m1", "spawn_agent", "{\"name\":\"helper\",\"task\":\"Write sub.txt\","
                        + "\"model\":\"sub\",\"isolation\":\"worktree\",\"mode\":\"background\"}"),
                Response.text("Started the helper."), Response.text("The helper is done."),
                Response.text("Noted."), Response.text("Noted."));
        var sub = new ScriptedLlmClient(
                Response.toolCall("s1", "write", "{\"path\":\"sub.txt\",\"content\":\"from sub\"}"),
                Response.text("Sub finished the file."));
        var router = new SessionRig.Router(main);
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.byModel = model -> "sub".equals(model) ? sub : router;
            rig.initRepo();
            rig.start("--yolo");
            ready(rig);
            rig.type("go\r");
            await("subagent spawned", () -> rig.session.worktrees() != null
                    && (!rig.session.worktrees().all().isEmpty() || Files.exists(rig.repo.resolve("sub.txt"))));
            await("worktree cleaned up", () -> rig.session.worktrees().all().isEmpty()
                    && Files.exists(rig.repo.resolve("sub.txt")));
            assertEquals("from sub", Files.readString(rig.repo.resolve("sub.txt")));
            var base = rig.repo.resolve(".jeorje").resolve("worktrees").resolve("session");
            await("directory removed", () -> !Files.exists(base.resolve("helper")));
            await("note in the history of the parent", () -> chatText(rig, InteractiveSession.MAIN)
                    .contains("worktree removed: helper"));
            assertEquals("", SessionRig.git(rig.repo, "branch", "--list", "jeorje/session/helper"),
                    "the branch is deleted");

            rig.type(CTRL_X_A);
            await("switcher pane", () -> rig.session.screen().paneActive());
            rig.type(DOWN + "\r");
            await("switched", () -> !rig.session.screen().foreground().equals(InteractiveSession.MAIN));
            assertFalse(rig.session.screen().paneActive());
            var shown = rig.session.screen().foreground();
            assertNotEquals("main", shown.value());
            await("history of the subagent", () -> chatText(rig, shown).contains("Sub finished the file."));
            assertTrue(rig.session.screen().status().text().startsWith(shown.value()),
                    rig.session.screen().status().text());
        }
    }

    @Test
    void theTrustAnswerIsStoredAndEnablesTheProjectSkills() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            var skill = Files.createDirectories(rig.repo.resolve(".agents/skills/greeter"));
            Files.writeString(skill.resolve("SKILL.md"),
                    "---\nname: greeter\ndescription: Greets people\n---\nSay hello.\n");
            rig.start();
            await("trust prompt", () -> rig.session.screen() != null && rig.session.screen().pendingPrompt() != null
                    && rig.session.screen().pendingPrompt().toolUse().tool().equals("trust_project"));
            assertNull(rig.session.slash(), "the agents are not built before the answer");
            rig.type("y");
            await("started", () -> rig.session.slash() != null && rig.terminalText().contains("ready"));
            var store = rig.home.resolve(".jeorje").resolve("trust.yaml");
            assertTrue(Files.isRegularFile(store));
            assertTrue(Files.readString(store).contains(rig.repo.getFileName().toString()), Files.readString(store));
            assertTrue(rig.session.slash().execute("/skills").output().contains("greeter"));
            assertTrue(rig.session.slash().execute("/trust").output().contains("already trusted"));

            rig.session.slash().execute("/trust revoke");
            assertFalse(rig.session.slash().execute("/skills").output().contains("greeter"));
            assertFalse(Files.readString(store).contains(rig.repo.getFileName().toString()));
            rig.session.slash().execute("/trust");
            assertTrue(rig.session.slash().execute("/skills").output().contains("greeter"));
        }
    }

    @Test
    void theFilesCommandListsTheTreeAndTheFilter() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            ready(rig);
            var list = rig.session.slash().execute("/files list");
            assertTrue(list.output().contains("README.md"), list.output());
            var filtered = rig.session.slash().execute("/files list md");
            assertTrue(filtered.output().lines().findFirst().orElseThrow().contains("(filter: md)"), filtered.output());
            assertTrue(filtered.output().contains("README.md"), filtered.output());
        }
    }

    @Test
    void theFilesCommandRejectsTheOldAddFormAndOtherWords() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            ready(rig);
            var add = rig.session.slash().execute("/files add README.md");
            assertTrue(add.error(), add.output());
            assertTrue(add.output().contains("Usage: /files [list [filter]]"), add.output());
            assertTrue(rig.session.attachments().isEmpty(), rig.session.attachments().toString());
            var nope = rig.session.slash().execute("/files nope");
            assertTrue(nope.error(), nope.output());
            assertTrue(nope.output().contains("Usage: /files [list [filter]]"), nope.output());
        }
    }

    @Test
    void theFilesCommandOpensThePaneAndEscClosesIt() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            ready(rig);
            rig.type("/files\r");
            await("browser pane", () -> rig.session.screen().paneActive());
            assertTrue(rig.session.screen().paneActive());
            rig.type("\u001b");
            await("pane gone", () -> !rig.session.screen().paneActive());
            assertFalse(rig.session.screen().paneActive());
        }
    }

    @Test
    void aMentionAddsTheFileToTheNextMessage() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient(Response.text("Read it.")));
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            ready(rig);
            rig.type("read @README.md\r");
            await("turn", () -> rig.session.agent().turns().size() == 1);
            var first = router.mainRequests.getFirst();
            var sent = new StringBuilder();
            first.messages().forEach(m -> sent.append(SessionRig.text(m)).append('\n'));
            assertTrue(sent.toString().contains("Files: README.md"), sent.toString());
            assertTrue(sent.toString().contains("@README.md"), sent.toString());
            assertTrue(rig.session.attachments().isEmpty(), rig.session.attachments().toString());
        }
    }

    @Test
    void aRefusedTrustPromptKeepsTheProjectSkillsOut() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            var skill = Files.createDirectories(rig.repo.resolve(".agents/skills/greeter"));
            Files.writeString(skill.resolve("SKILL.md"),
                    "---\nname: greeter\ndescription: Greets people\n---\nSay hello.\n");
            rig.start();
            await("trust prompt", () -> rig.session.screen() != null && rig.session.screen().pendingPrompt() != null);
            rig.type("n");
            await("started", () -> rig.session.slash() != null && rig.terminalText().contains("ready"));
            assertFalse(rig.session.slash().execute("/skills").output().contains("greeter"));
            assertFalse(Files.exists(rig.home.resolve(".jeorje").resolve("trust.yaml")));
        }
    }

    @Test
    void theStatusLineShowsTheBranchPlanAndYoloMarkers() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient(Response.text("Plan mode noted.")));
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start("--yolo");
            ready(rig);
            await("branch and yolo", () -> {
                var text = rig.session.screen().status().text();
                return text.contains("| main |") && text.contains("YOLO") && !text.contains("PLAN");
            });
            rig.type("/plan\r");
            await("plan marker", () -> rig.session.screen().status().text().contains("PLAN"));
            rig.type("/plan cancel\r");
            await("plan off", () -> !rig.session.screen().status().text().contains("PLAN"));
        }
    }

    @Test
    void theStatusLineShowsTheImplementCounter() throws Exception {
        var blocked = new Response[] {
            Response.toolCall("b1", "story_blocked", "{\"reason\":\"cannot do it\"}"), Response.text("Blocked."),
            Response.toolCall("b2", "story_blocked", "{\"reason\":\"cannot do it\"}"), Response.text("Blocked.")};
        var router = new SessionRig.Router(new ScriptedLlmClient()).story("S1", blocked);
        try (var rig = new SessionRig(tmp, router, null)) {
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
            ready(rig);
            rig.type("/implement one\r");
            await("counter", () -> rig.session.screen().status().text().contains("IMPLEMENT 0/1"));
            await("run ended", () -> !rig.session.orchestrator().active());
        }
    }
}
