package org.github.lscoughlin.jeorje.cli.interactive;

import static org.github.lscoughlin.jeorje.cli.interactive.SessionRig.await;
import static org.github.lscoughlin.jeorje.cli.interactive.SessionRig.git;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.implement.StoryState;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class InteractiveSessionTest {
    @TempDir Path tmp;

    private static final String CONFIG = """
            permissions:
              rules:
                - tool: write
                  decision: allow
            models:
              advisor: none
            """;

    private static final String PLAN_JSON = """
            {"title":"Two Files","goal":"Make two files.","name":"two-files","stories":[
             {"id":"S1","title":"First file","goal":"Create s1.txt.","touches":["s1.txt"],
              "context":"Create the file s1.txt in the root of the repository.",
              "acceptanceCriteria":["The file s1.txt exists."],"verification":"`test -f s1.txt`",
              "tasks":[{"id":"S1.T1","text":"Create s1.txt."}]},
             {"id":"S2","title":"Second file","goal":"Create s2.txt.","touches":["s2.txt"],
              "context":"Create the file s2.txt in the root of the repository.",
              "acceptanceCriteria":["The file s2.txt exists."],"verification":"`test -f s2.txt`",
              "tasks":[{"id":"S2.T1","text":"Create s2.txt."}]}]}
            """.replace("\n", "");

    private static Response[] worker(String story, String file) {
        return new Response[] {
            Response.toolCall(story + "-c1", "write", "{\"path\":\"" + file + "\",\"content\":\"" + story + "\"}"),
            Response.toolCall(story + "-c2", "task_done", "{\"taskId\":\"" + story + ".T1\",\"note\":\"created\"}"),
            Response.toolCall(story + "-c3", "story_done", "{\"summary\":\"DRAFT " + story
                    + "\",\"filesChanged\":[\"" + file + "\"]}"),
            Response.text("Finished " + story + ".")
        };
    }

    /** Answers permission prompts with "y" until the condition holds. */
    private static void answering(SessionRig rig, String what, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        var last = new Object[1];
        await(what, () -> {
            var prompt = rig.session.screen().pendingPrompt();
            if (prompt != null && prompt != last[0]) {
                last[0] = prompt;
                try {
                    rig.type("y");
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return condition.getAsBoolean();
        });
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void fullSessionWithChatPlanImplementSwitchAndExit() throws Exception {
        var main = new ScriptedLlmClient(
                Response.text("Hi there."),
                Response.toolCall("b1", "bash", "{\"command\":\"mkdir -p target && touch target/touched\"}"),
                Response.text("Ran it."),
                Response.toolCall("p1", "plan_submit", PLAN_JSON),
                Response.text("The plan is ready."));
        var router = new SessionRig.Router(main)
                .story("S1", worker("S1", "s1.txt"))
                .story("S2", worker("S2", "s2.txt"));
        try (var rig = new SessionRig(tmp, router, CONFIG)) {
            rig.initRepo();
            var before = rig.terminal.getAttributes();
            rig.start();
            await("screen up", () -> rig.session.screen() != null && rig.session.agent() != null
                    && rig.terminalText().contains("ready"));

            // The mouse is tracked while the session runs.
            assertTrue(rig.terminal.hasMouseSupport(), "the fake xterm terminal reports mouse support");
            await("mouse on", () -> rig.terminal.getCurrentMouseTracking() != Terminal.MouseTracking.Off);

            // A chat turn.
            rig.type("hello\r");
            await("first turn", () -> rig.session.agent().turns().size() == 1);
            assertTrue(rig.session.screen().chat(new AgentId("main")).logicalLines().stream()
                    .anyMatch(l -> l.toString().contains("Hi there.")));

            // A tool call with a permission prompt.
            rig.type("run the shell\r");
            await("prompt", () -> rig.session.screen().pendingPrompt() != null);
            assertEquals("main", rig.session.screen().pendingPrompt().agent().value());
            assertEquals("bash", rig.session.screen().pendingPrompt().toolUse().tool());
            rig.type("y");
            await("second turn", () -> rig.session.agent().turns().size() == 2);
            assertTrue(Files.exists(rig.repo.resolve("target/touched")));

            // Plan mode writes a plan.
            rig.type("/plan Build two files\r");
            await("plan mode", () -> rig.session.planMode().active());
            await("plan written", () -> rig.session.planMode().submittedPlan().isPresent()
                    && rig.session.agent().turns().size() == 3);
            assertTrue(rig.session.screen().status().text().contains("PLAN"));
            var plan = rig.session.planMode().submittedPlan().orElseThrow();
            assertTrue(Files.readString(plan).contains("## Story S2: Second file"));
            rig.type("/plan done\r");
            await("plan done", () -> !rig.session.planMode().active());
            assertFalse(rig.session.screen().status().text().contains("PLAN"));
            git(rig.repo, "add", "-A");
            git(rig.repo, "commit", "-q", "-m", "add the plan");

            // /implement with two stories in worktrees.
            rig.type("/implement two-files\r");
            var sawImplement = new boolean[1];
            answering(rig, "implement run", () -> {
                sawImplement[0] |= rig.session.screen().status().text().contains("IMPLEMENT");
                var rows = rig.session.orchestrator().rows();
                return rows.size() == 2 && rows.stream().allMatch(r -> r.state() == StoryState.DONE)
                        && !rig.session.orchestrator().active();
            });
            assertTrue(sawImplement[0], "the status line shows IMPLEMENT");
            assertEquals("S1", Files.readString(rig.repo.resolve("s1.txt")));
            assertEquals("S2", Files.readString(rig.repo.resolve("s2.txt")));
            assertTrue(Files.readString(plan).contains("- [x] S1.T1"));

            // Switch into a worker and back.
            var worker = rig.session.roster().agents().stream().filter(e -> e.name().startsWith("S1")).findFirst()
                    .orElseThrow();
            rig.type("/agents " + (rig.session.roster().agents().indexOf(worker) + 1) + "\r");
            await("switch", () -> rig.session.screen().foreground().equals(worker.id()));
            assertTrue(rig.session.screen().chat(worker.id()).logicalLines().stream()
                    .anyMatch(l -> l.toString().contains("Finished S1.")));
            rig.type("/back\r");
            await("back", () -> rig.session.screen().foreground().value().equals("main"));

            // Clean exit.
            rig.type("/quit\r");
            assertEquals(0, rig.result.get(30, TimeUnit.SECONDS));

            var after = rig.terminal.getAttributes();
            assertEquals(before.getLocalFlag(Attributes.LocalFlag.ICANON), after.getLocalFlag(Attributes.LocalFlag.ICANON));
            assertEquals(before.getLocalFlag(Attributes.LocalFlag.ECHO), after.getLocalFlag(Attributes.LocalFlag.ECHO));
            assertEquals(Terminal.MouseTracking.Off, rig.terminal.getCurrentMouseTracking());
            assertTrue(rig.terminalText().contains("\u001b[?1049l") || rig.terminalText().contains("\u001b[?47l"),
                    "the alternate screen is left");
            assertNotNull(rig.session.worktrees());
            assertEquals("", git(rig.repo, "branch", "--list", "jeorje/*"), "worker branches are gone");
        }
    }
}
