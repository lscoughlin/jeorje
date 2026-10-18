package org.github.lscoughlin.jeorje.tools.story;

import static org.github.lscoughlin.jeorje.tools.story.StoryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.agent.SystemPromptBuilder;
import org.github.lscoughlin.jeorje.core.implement.ImplementReport;
import org.github.lscoughlin.jeorje.core.implement.ImplementRequest;
import org.github.lscoughlin.jeorje.core.implement.StoryState;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.plan.PlanParser;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImplementOrchestratorTest {
    @TempDir Path tmp;

    private static ImplementReport run(Rig rig) throws Exception {
        return rig.orchestrator.start(ImplementRequest.of(rig.plan)).get(60, TimeUnit.SECONDS);
    }

    private static StoryState state(ImplementReport report, String id) {
        return report.stories().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow().state();
    }

    @Test
    void oneStoryRunsFromWorktreeToMergeAndCleanup() throws Exception {
        var plan = planText("approved", "test -f s1.txt", Spec.of("S1", "First thing", "s1.txt"));
        var brain = new Brain().script("S1", simple("S1", "s1.txt", "one"));
        try (var rig = new Rig(tmp, true, plan, 2, brain, null, 2, null)) {
            var report = run(rig);
            assertEquals("done", report.status(), report.render());
            assertEquals("passed", report.verification());
            assertEquals(StoryState.DONE, state(report, "S1"));
            assertEquals("one", Files.readString(rig.root.resolve("s1.txt")));
            var parsed = PlanParser.parse(rig.plan);
            assertTrue(parsed.story("S1").orElseThrow().done());
            assertEquals("done", parsed.status());
            assertTrue(rig.planFile().contains("- [x] S1.T1"));
            assertEquals("", g(rig.root, "branch", "--list", "jeorje/*"), "the branch is deleted");
            assertFalse(Files.exists(rig.root.resolve(".jeorje/worktrees/demo/s1")), "the worktree is removed");
            assertTrue(rig.store.all().stream().anyMatch(c -> c.kind().equals(SubagentRuntime.SUMMARY_KIND)
                && c.agentId().equals("orchestrator") && c.text().contains("Done: work")));
            assertTrue(rig.notices.isEmpty(), rig.notices.toString());
            assertTrue(report.render().contains("S1 First thing: done"));
        }
    }

    @Test
    void workerContextIsTheSystemPromptAndOneUserMessageWithTheStory() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "First thing", "s1.txt"),
            Spec.of("S2", "Second thing", "s2.txt").after("S1"));
        var brain = new Brain().script("S1", simple("S1", "s1.txt", "one")).script("S2", simple("S2", "s2.txt", "two"))
            .summary("S1", summaryText("made s1", "s1.txt"));
        try (var rig = new Rig(tmp, true, plan, 1, brain, null, 2, null)) {
            var original = PlanParser.parse(rig.plan);
            var report = run(rig);
            assertEquals("done", report.status(), report.render());
            var first = brain.of("S1").get(0);
            // Part 2: one user message with the story text, and nothing else.
            assertEquals(1, first.messages().size());
            assertEquals(1, first.messages().get(0).content().size());
            assertEquals(original.story("S1").orElseThrow().render(), text(first.messages().get(0)));
            // Part 1: the normal system prompt. The runtime adds the name of the worker and the task to it.
            var worktree = rig.root.resolve(".jeorje/worktrees/demo/s1");
            var instructions = "You are the subagent \"S1 First thing\" of the agent \"orchestrator\".\nYour task:\n"
                + original.story("S1").orElseThrow().render();
            var expected = SystemPromptBuilder.build(new SystemPromptBuilder.Parts(null,
                first.tools(), worktree, System.getProperty("os.name", "unknown"), LocalDate.now(), instructions, null));
            assertEquals(expected, first.system());
            // Nothing of the plan, the other stories, or the orchestrator.
            var everything = first.system() + text(first.messages().get(0));
            assertFalse(everything.contains(PLAN_TITLE));
            assertFalse(everything.contains(PLAN_GOAL));
            assertFalse(everything.contains("S2"));
            assertFalse(everything.contains("Second thing"));
            assertFalse(everything.contains("Goal of S2"));
            // The tool set.
            var names = first.tools().stream().map(ToolDefinition::name).toList();
            assertTrue(names.containsAll(List.of("task_done", "story_done", "story_blocked", "story_read",
                "sync_done", "sync_abort", "agent_message", "agent_ask", "agent_answer", "agent_status", "recall",
                "put_file", "shell")), names.toString());
            for (var banned : List.of("spawn_agent", "plan_submit", "ask_user", "advisor")) {
                assertFalse(names.contains(banned), banned);
            }
            // The second worker has the handoff from the final summary of the first.
            var second = text(brain.of("S2").get(0).messages().get(0));
            assertTrue(second.contains("### Handoff"), second);
            assertTrue(second.contains("From S1 (First thing):"));
            assertTrue(second.contains("- Files: s1.txt"));
        }
    }

    // ---- scheduling ----

    @Test
    void parallelLimitAndDependencyOrder() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "One", "s1.txt"), Spec.of("S2", "Two", "s2.txt"),
            Spec.of("S3", "Three", "s3.txt"), Spec.of("S4", "Four", "s4.txt").after("S1", "S2"));
        var barrier = new java.util.concurrent.CountDownLatch(2);
        var brain = new Brain();
        for (var id : List.of("S1", "S2")) {
            var steps = simple(id, id.toLowerCase() + ".txt", id);
            brain.worker(id, (r, i, c) -> {
                if (i == 0) {
                    barrier.countDown();
                    assertTrue(barrier.await(10, TimeUnit.SECONDS), "S1 and S2 run at the same time");
                }
                return i < steps.length ? steps[i] : Response.text("done");
            });
        }
        brain.script("S3", simple("S3", "s3.txt", "3")).script("S4", simple("S4", "s4.txt", "4"));
        try (var rig = new Rig(tmp, true, plan, 2, brain, null, 2, null)) {
            var report = run(rig);
            assertEquals("done", report.status(), report.render());
            assertEquals(2, rig.maxConcurrent());
            var order = rig.events.stream().map(e -> e instanceof org.github.lscoughlin.jeorje.core.implement.ImplementEvent.StoryStarted st
                ? "start " + st.storyId() : e instanceof org.github.lscoughlin.jeorje.core.implement.ImplementEvent.StoryChanged ch
                && ch.state() == StoryState.DONE ? "done " + ch.storyId() : "").filter(x -> !x.isEmpty()).toList();
            assertTrue(order.indexOf("start S3") > Math.min(order.indexOf("done S1"), order.indexOf("done S2")),
                "S3 waits for a free slot: " + order);
            assertTrue(order.indexOf("start S4") > order.indexOf("done S1")
                && order.indexOf("start S4") > order.indexOf("done S2"), "S4 waits for S1 and S2: " + order);
            for (var f : List.of("s1.txt", "s2.txt", "s3.txt", "s4.txt")) assertTrue(Files.exists(rig.root.resolve(f)), f);
        }
    }

    @Test
    void storiesWithSharedTouchesNeverRunAtTheSameTime() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "One", "src/a1.txt").touching("src/"),
            Spec.of("S2", "Two", "src/a/a2.txt").touching("src/a/"), Spec.of("S3", "Three", "docs/d3.txt"));
        var barrier = new java.util.concurrent.CountDownLatch(2);
        var steps1 = simple("S1", "src/a1.txt", "1");
        var steps3 = simple("S3", "docs/d3.txt", "3");
        var brain = new Brain().script("S2", simple("S2", "src/a/a2.txt", "2"));
        brain.worker("S1", (r, i, c) -> {
            if (i == 0) {
                barrier.countDown();
                assertTrue(barrier.await(10, TimeUnit.SECONDS));
            }
            return i < steps1.length ? steps1[i] : Response.text("done");
        });
        brain.worker("S3", (r, i, c) -> {
            if (i == 0) {
                barrier.countDown();
                assertTrue(barrier.await(10, TimeUnit.SECONDS));
            }
            return i < steps3.length ? steps3[i] : Response.text("done");
        });
        try (var rig = new Rig(tmp, true, plan, 3, brain, null, 2, null)) {
            var report = run(rig);
            assertEquals("done", report.status(), report.render());
            var kinds = rig.events.stream().map(Object::toString).toList();
            var s1Done = indexOf(rig, "S1", StoryState.DONE);
            var s2Start = rig.events.indexOf(rig.events.stream().filter(e -> e instanceof org.github.lscoughlin.jeorje.core.implement.ImplementEvent.StoryStarted st
                && st.storyId().equals("S2")).findFirst().orElseThrow());
            assertTrue(s2Start > s1Done, "S2 shares src/ with S1: " + kinds);
            assertEquals(2, rig.maxConcurrent());
        }
    }

    private static int indexOf(Rig rig, String story, StoryState state) {
        for (var i = 0; i < rig.events.size(); i++) {
            if (rig.events.get(i) instanceof org.github.lscoughlin.jeorje.core.implement.ImplementEvent.StoryChanged c
                && c.storyId().equals(story) && c.state() == state) return i;
        }
        return -1;
    }

    // ---- story_read ----

    private static Response[] concat(Response[]... parts) {
        var all = new java.util.ArrayList<Response>();
        for (var p : parts) all.addAll(List.of(p));
        return all.toArray(new Response[0]);
    }

    @Test
    void storyReadListsStoriesAndReturnsTheTextOfAnyStory() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "First thing", "s1.txt"),
            Spec.of("S2", "Second thing", "s2.txt").after("S1"));
        var brain = new Brain().script("S1", concat(new Response[] {
            call("r1", "story_read", "{}"), call("r2", "story_read", "{\"storyId\":\"S2\"}"),
            call("r3", "story_read", "{\"storyId\":\"S9\"}") }, simple("S1", "s1.txt", "one")))
            .script("S2", simple("S2", "s2.txt", "two"));
        try (var rig = new Rig(tmp, true, plan, 2, brain, null, 2, null)) {
            var report = run(rig);
            assertEquals("done", report.status(), report.render());
            var results = toolResults(brain.of("S1").get(brain.of("S1").size() - 1));
            assertTrue(results.get(0).contains("S1 | First thing | running | attempt 1"), results.get(0));
            assertTrue(results.get(0).contains("S2 | Second thing | pending | attempt 0"), results.get(0));
            assertTrue(results.get(1).contains("## Story S2: Second thing"));
            assertTrue(results.get(1).contains("- **Depends on:** S1"));
            assertTrue(results.get(1).contains("- [ ] S2.T1"));
            assertTrue(results.get(1).contains("State: pending"));
            assertFalse(results.get(0).contains(PLAN_TITLE) || results.get(1).contains(PLAN_TITLE));
            assertFalse(results.get(0).contains(PLAN_GOAL) || results.get(1).contains(PLAN_GOAL));
            assertTrue(results.get(2).contains("no story S9"), results.get(2));
        }
    }

    @Test
    void storyDoneNeedsAllTasksDone() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "First thing", "s1.txt"));
        var brain = new Brain().script("S1", concat(new Response[] {
            call("d0", "story_done", "{\"summary\":\"too early\"}") }, simple("S1", "s1.txt", "one")));
        try (var rig = new Rig(tmp, true, plan, 1, brain, null, 2, null)) {
            var report = run(rig);
            assertEquals("done", report.status(), report.render());
            assertTrue(toolResults(brain.of("S1").get(1)).get(0).contains("S1.T1"));
        }
    }

    // ---- questions ----

    @Test
    void workersAskAndAnswerEachOther() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "Asker", "s1.txt"), Spec.of("S2", "Answerer", "s2.txt"));
        var s2Started = new java.util.concurrent.CountDownLatch(1);
        var answered = new java.util.concurrent.atomic.AtomicBoolean();
        var phase = new java.util.concurrent.atomic.AtomicInteger();
        var s1Steps = simple("S1", "s1.txt", "asked");
        var s2Steps = simple("S2", "s2.txt", "answered");
        var brain = new Brain();
        brain.worker("S1", (r, i, c) -> {
            if (i == 0) {
                assertTrue(s2Started.await(10, TimeUnit.SECONDS));
                return call("a1", "agent_ask", "{\"to\":\"S2\",\"question\":\"Which name?\"}");
            }
            return i - 1 < s1Steps.length ? s1Steps[i - 1] : Response.text("done");
        });
        brain.worker("S2", (r, i, c) -> {
            if (i == 0) s2Started.countDown();
            if (!answered.get()) {
                // The runtime adds a question at the step boundary as text in the last user message.
                var tail = r.messages().getLast().content().stream().map(Object::toString)
                    .collect(java.util.stream.Collectors.joining("\n"));
                var seen = tail;
                var m = java.util.regex.Pattern.compile("\\[question from [^\\]]*, id (q\\d+)\\]").matcher(seen);
                if (m.find()) {
                    answered.set(true);
                    return call("ans", "agent_answer", "{\"questionId\":\"" + m.group(1) + "\",\"text\":\"use beta\"}");
                }
                Thread.sleep(20);
                return call("poll" + i, "story_read", "{}");
            }
            var k = phase.getAndIncrement();
            return k < s2Steps.length ? s2Steps[k] : Response.text("done");
        });
        try (var rig = new Rig(tmp, true, plan, 2, brain, null, 2, null)) {
            var report = run(rig);
            assertEquals("done", report.status(), report.render());
            var results = toolResults(brain.of("S1").get(brain.of("S1").size() - 1));
            assertEquals("use beta", results.get(0));
            var question = rig.store.all().stream().filter(ch -> ch.text().contains("Which name?")).count();
            assertTrue(question > 0, "the question is in the history");
        }
    }

    // ---- summaries and review ----

    @Test
    void reviewGetsTheDraftAndTheHistoryGetsTheFinalSummaryAfterTheMerge() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "First thing", "s1.txt"));
        var seen = new java.util.concurrent.CopyOnWriteArrayList<org.github.lscoughlin.jeorje.core.implement.Reviewer.Request>();
        org.github.lscoughlin.jeorje.core.implement.Reviewer reviewer = request -> {
            seen.add(request);
            return org.github.lscoughlin.jeorje.core.implement.Reviewer.Verdict.approve();
        };
        var brain = new Brain().script("S1", simple("S1", "s1.txt", "one"))
            .summary("S1", summaryText("FINAL-MARKER done", "s1.txt"));
        try (var rig = new Rig(tmp, true, plan, 1, brain, reviewer, 2, null)) {
            var report = run(rig);
            assertEquals("done", report.status(), report.render());
            assertEquals(1, seen.size());
            var review = seen.get(0);
            assertTrue(review.draftSummary().contains("DRAFT-S1"));
            assertFalse(review.draftSummary().contains("FINAL-MARKER"));
            assertTrue(review.storyText().contains("## Story S1: First thing"));
            assertTrue(review.changedFiles().contains("s1.txt"), review.changedFiles().toString());
            assertTrue(review.diff().contains("+one"), review.diff());
            var chunk = rig.store.all().stream().filter(ch -> ch.kind().equals(SubagentRuntime.SUMMARY_KIND))
                .findFirst().orElseThrow();
            assertTrue(chunk.text().contains("FINAL-MARKER"));
            assertFalse(chunk.text().contains("DRAFT-S1"));
            assertEquals(1, brain.summaryRequests.size());
            var body = text(brain.summaryRequests.get(0).messages().get(0));
            assertTrue(body.contains("merged into main"), "the summary is written after the merge back: " + body);
            assertEquals("FINAL-MARKER done", report.stories().get(0).result());
            assertEquals(List.of("s1.txt"), report.stories().get(0).files());
        }
    }

    @Test
    void rejectedReviewStartsANewWorkerWithThePreviousAttempt() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "First thing", "s1.txt"));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        org.github.lscoughlin.jeorje.core.implement.Reviewer reviewer = request ->
            calls.getAndIncrement() == 0 ? org.github.lscoughlin.jeorje.core.implement.Reviewer.Verdict.reject(
                List.of("missing the X handling"))
                : org.github.lscoughlin.jeorje.core.implement.Reviewer.Verdict.approve();
        var brain = new Brain().script("S1", concat(simple("S1", "s1.txt", "v1"), simple("S1", "s1.txt", "v2")));
        try (var rig = new Rig(tmp, true, plan, 1, brain, reviewer, 2, null)) {
            var report = run(rig);
            assertEquals("done", report.status(), report.render());
            assertEquals(2, calls.get());
            assertEquals(2, report.stories().get(0).attempts());
            var second = text(brain.of("S1").get(4).messages().get(0));
            assertTrue(second.contains("### Previous attempt"), second);
            assertTrue(second.contains("Review rejected: missing the X handling"), second);
            assertFalse(text(brain.of("S1").get(0).messages().get(0)).contains("Previous attempt"));
            assertEquals("v2", Files.readString(rig.root.resolve("s1.txt")));
            assertEquals("", g(rig.root, "branch", "--list", "jeorje/*"));
            assertFalse(Files.exists(rig.root.resolve(".jeorje/worktrees/demo/s1")));
            assertFalse(Files.exists(rig.root.resolve(".jeorje/worktrees/demo/s1-a2")));
            assertTrue(rig.planFile().contains("- [x] S1.T1"));
            var reviews = rig.events.stream().filter(e -> e instanceof org.github.lscoughlin.jeorje.core.implement.ImplementEvent.Reviewed)
                .map(e -> ((org.github.lscoughlin.jeorje.core.implement.ImplementEvent.Reviewed) e).approved()).toList();
            assertEquals(List.of(false, true), reviews);
        }
    }

    // ---- failure ----

    @Test
    void blockedStoryBlocksItsDependentsAndTheOthersFinish() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "Fails", "s1.txt"),
            Spec.of("S2", "Waits", "s2.txt").after("S1"), Spec.of("S3", "Independent", "s3.txt"));
        var blocked = new Response[] { call("b1", "story_blocked", "{\"reason\":\"the API does not exist\"}"),
            Response.text("Stopped.") };
        var brain = new Brain().script("S1", concat(blocked, blocked)).script("S3", simple("S3", "s3.txt", "3"));
        try (var rig = new Rig(tmp, true, plan, 2, brain, null, 2, null)) {
            var report = run(rig);
            assertEquals("blocked", report.status(), report.render());
            assertEquals(StoryState.BLOCKED, state(report, "S1"));
            assertEquals(StoryState.BLOCKED, state(report, "S2"));
            assertEquals(StoryState.DONE, state(report, "S3"));
            assertEquals(2, report.stories().get(0).attempts());
            assertTrue(report.stories().get(0).reason().contains("the API does not exist"));
            assertTrue(report.stories().get(1).reason().contains("depends on the blocked story S1"));
            assertTrue(brain.of("S2").isEmpty(), "the dependent never started");
            assertTrue(text(brain.of("S1").get(2).messages().get(0)).contains("### Previous attempt"));
            var file = rig.planFile();
            assertTrue(file.contains("- [!] S1.T1"), file);
            assertTrue(file.contains("- [ ] S2.T1"), file);
            assertTrue(file.contains("- [x] S3.T1"), file);
            assertEquals("blocked", PlanParser.parse(rig.plan).status());
            assertEquals("IMPLEMENT 1/3 (2 blocked)", rig.orchestrator.statusLabel());
            assertTrue(Files.isDirectory(rig.root.resolve(".jeorje/worktrees/demo/s1-a2")), "a blocked story keeps its worktree");
            assertTrue(report.render().contains("reason: story_blocked: the API does not exist"));
        }
    }

    @Test
    void failedPlanVerificationBlocksThePlan() throws Exception {
        var plan = planText("approved", "test -f nothing.txt", Spec.of("S1", "First thing", "s1.txt"));
        var brain = new Brain().script("S1", simple("S1", "s1.txt", "one"));
        try (var rig = new Rig(tmp, true, plan, 1, brain, null, 2, null)) {
            var report = run(rig);
            assertEquals("blocked", report.status());
            assertEquals("failed", report.verification());
            assertEquals("blocked", PlanParser.parse(rig.plan).status());
            assertEquals(StoryState.DONE, state(report, "S1"));
        }
    }

    // ---- conflicts ----

    private Brain conflictBrain(boolean resolve) {
        var s1 = simple("S1", "shared.txt", "from S1\n");
        var s2 = simple("S2", "shared.txt", "from S2\n");
        var shared = tmp.resolve("repo").resolve("shared.txt");
        var brain = new Brain().script("S1", s1);
        brain.worker("S2", (r, i, c) -> {
            switch (i) {
                case 0, 1, 2 -> { return s2[i]; }
                case 3 -> {
                    waitUntil(() -> Files.exists(shared));
                    return Response.text("Finished S2.");
                }
                case 4 -> {
                    assertTrue(text(r.messages().get(r.messages().size() - 1)).contains("conflicts with main"),
                        text(r.messages().get(r.messages().size() - 1)));
                    return resolve ? call("w1", "put_file", "{\"path\":\"shared.txt\",\"content\":\"both\\n\"}")
                        : call("w1", "sync_abort", "{\"reason\":\"cannot merge the two versions\"}");
                }
                case 5 -> { return resolve ? call("w2", "sync_done", "{\"notes\":\"kept both lines\"}") : Response.text("Gave up."); }
                default -> { return Response.text("Resolved."); }
            }
        });
        return brain;
    }

    private static final String TWO_STORIES_ONE_FILE = null;

    private String conflictPlan() {
        return planText("approved", null, Spec.of("S1", "First writer", "shared.txt").touching("s1-area/"),
            Spec.of("S2", "Second writer", "shared.txt").touching("s2-area/"));
    }

    @Test
    void aWorkerResolvesAConflictOfTheSync() throws Exception {
        var brain = conflictBrain(true);
        try (var rig = new Rig(tmp, true, conflictPlan(), 2, brain, null, 2, null)) {
            var report = run(rig);
            assertEquals("done", report.status(), report.render());
            assertEquals("both\n", Files.readString(rig.root.resolve("shared.txt")));
            var id = rig.orchestrator.runtime().find("S2").orElseThrow();
            var states = rig.subEvents.stream().filter(e -> e instanceof org.github.lscoughlin.jeorje.core.subagent.SubagentEvent.StateChanged sc
                && sc.agent().equals(id)).map(e -> ((org.github.lscoughlin.jeorje.core.subagent.SubagentEvent.StateChanged) e).to()).toList();
            assertTrue(states.contains(org.github.lscoughlin.jeorje.core.subagent.AgentLifecycle.RESOLVING), states.toString());
            var summaryRequest = brain.summaryRequests.stream().map(r -> text(r.messages().get(0)))
                .filter(t -> t.contains("S2 Second writer")).findFirst().orElseThrow();
            assertTrue(summaryRequest.contains("Conflicts resolved during the sync: shared.txt"), summaryRequest);
            assertEquals(1, report.stories().get(1).attempts());
        }
    }

    @Test
    void syncAbortBlocksTheStoryAndARepairStoryFinishesIt() throws Exception {
        var brain = conflictBrain(false);
        var repairSteps = new Response[] {
            null,
            call("rp2", "put_file", "{\"path\":\"shared.txt\",\"content\":\"both\\n\"}"),
            call("rp3", "shell", "{\"command\":\"git add -A && git commit -q -m resolved\"}"),
            Response.builder().toolUse("rp4", "task_done", "{\"taskId\":\"S2-repair.T1\"}")
                .toolUse("rp5", "task_done", "{\"taskId\":\"S2-repair.T2\"}")
                .toolUse("rp6", "task_done", "{\"taskId\":\"S2-repair.T3\"}").stop("tool_use").build(),
            call("rp7", "story_done", "{\"summary\":\"DRAFT-repair merged\",\"filesChanged\":[\"shared.txt\"]}"),
            Response.text("Repaired.")
        };
        brain.worker("S2-repair", (r, i, c) -> {
            if (i == 0) {
                var first = text(r.messages().get(0));
                var m = java.util.regex.Pattern.compile("The branch (\\S+) is kept").matcher(first);
                assertTrue(m.find(), first);
                assertTrue(first.contains("cannot merge the two versions"), first);
                assertTrue(first.contains("## Story S2: Second writer"), "the repair story has the blocked story");
                assertTrue(first.contains("#### Related story S1") || first.contains("Related story S1"), first);
                return call("rp1", "shell", "{\"command\":\"git merge --no-edit " + m.group(1) + "\"}");
            }
            return i < repairSteps.length ? repairSteps[i] : Response.text("done");
        });
        try (var rig = new Rig(tmp, true, conflictPlan(), 2, brain, null, 2, null)) {
            var report = run(rig);
            assertEquals("blocked", report.status(), report.render());
            var s2 = report.stories().get(1);
            assertEquals(StoryState.BLOCKED, s2.state());
            assertEquals(1, s2.attempts(), "a sync_abort is not retried");
            assertTrue(s2.reason().contains("sync_abort: cannot merge the two versions"), s2.reason());
            assertTrue(s2.reason().contains("shared.txt"), s2.reason());
            assertEquals("from S1\n", Files.readString(rig.root.resolve("shared.txt")), "the source is unchanged");
            assertTrue(Files.isDirectory(rig.root.resolve(".jeorje/worktrees/demo/s2")));
            assertFalse(g(rig.root, "branch", "--list", "jeorje/demo/s2").isEmpty());
            assertEquals(StoryState.BLOCKED, rig.orchestrator.rows().get(1).state());

            var repaired = rig.orchestrator.repair("S2").get(60, TimeUnit.SECONDS);
            assertEquals("done", repaired.status(), repaired.render());
            assertEquals("both\n", Files.readString(rig.root.resolve("shared.txt")));
            assertEquals(StoryState.DONE, state(repaired, "S2"));
            assertEquals(StoryState.DONE, state(repaired, "S2-repair"));
            assertTrue(rig.planFile().contains("- [x] S2.T1"));
            assertEquals("", g(rig.root, "branch", "--list", "jeorje/*"), "all branches are cleaned up");
            assertEquals("done", PlanParser.parse(rig.plan).status());
        }
    }

    // ---- pause, resume, stop, restart ----

    @Test
    void pauseStopsNewStoriesAndResumeContinues() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "One", "s1.txt"), Spec.of("S2", "Two", "s2.txt").after("S1"));
        var running = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var steps = simple("S1", "s1.txt", "1");
        var brain = new Brain().script("S2", simple("S2", "s2.txt", "2"));
        brain.worker("S1", (r, i, c) -> {
            if (i == 0) {
                running.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return i < steps.length ? steps[i] : Response.text("done");
        });
        try (var rig = new Rig(tmp, true, plan, 1, brain, null, 2, null)) {
            var future = rig.orchestrator.start(ImplementRequest.of(rig.plan));
            assertTrue(running.await(10, TimeUnit.SECONDS));
            rig.orchestrator.pause();
            release.countDown();
            waitUntil(() -> rig.events.stream().anyMatch(e -> e instanceof org.github.lscoughlin.jeorje.core.implement.ImplementEvent.RunControl rc
                && rc.action().equals("paused")));
            assertFalse(future.isDone());
            assertTrue(brain.of("S2").isEmpty());
            var rows = rig.orchestrator.rows();
            assertEquals(StoryState.DONE, rows.get(0).state());
            assertEquals(StoryState.PENDING, rows.get(1).state());
            assertTrue(rig.orchestrator.status().contains("pending"));
            assertEquals("paused", RunStateStore().load().orElseThrow().status());
            rig.orchestrator.resume();
            var report = future.get(60, TimeUnit.SECONDS);
            assertEquals("done", report.status(), report.render());
            assertEquals(StoryState.DONE, state(report, "S2"));
        }
    }

    private org.github.lscoughlin.jeorje.core.implement.RunStateStore RunStateStore() {
        return org.github.lscoughlin.jeorje.core.implement.RunStateStore.file(tmp.resolve("state").resolve("implement.json"));
    }

    @Test
    void stopCancelsTheWorkersAndEndsTheRun() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "One", "s1.txt"));
        var started = new java.util.concurrent.CountDownLatch(1);
        var brain = new Brain().worker("S1", (r, i, c) -> {
            started.countDown();
            var gone = new java.util.concurrent.CountDownLatch(1);
            c.onCancel(gone::countDown);
            gone.await(30, TimeUnit.SECONDS);
            return Response.text("cancelled");
        });
        try (var rig = new Rig(tmp, true, plan, 1, brain, null, 2, null)) {
            var future = rig.orchestrator.start(ImplementRequest.of(rig.plan));
            assertTrue(started.await(10, TimeUnit.SECONDS));
            rig.orchestrator.stop();
            var report = future.get(60, TimeUnit.SECONDS);
            assertEquals("stopped", report.status());
            assertEquals(StoryState.PENDING, state(report, "S1"));
            assertEquals("in-progress", PlanParser.parse(rig.plan).status());
            assertEquals("stopped", RunStateStore().load().orElseThrow().status());
            assertFalse(Files.exists(rig.root.resolve(".jeorje/worktrees/demo/s1")));
            assertFalse(rig.orchestrator.active());
        }
    }

    @Test
    void anInterruptedRunResumesFromTheCheckboxesAndTheRunState() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "One", "s1.txt"), Spec.of("S2", "Two", "s2.txt").after("S1"));
        var started = new java.util.concurrent.CountDownLatch(1);
        var first = new Brain().script("S1", simple("S1", "s1.txt", "1")).worker("S2", (r, i, c) -> {
            started.countDown();
            var gone = new java.util.concurrent.CountDownLatch(1);
            c.onCancel(gone::countDown);
            gone.await(30, TimeUnit.SECONDS);
            return Response.text("cancelled");
        });
        try (var rig = new Rig(tmp, true, plan, 1, first, null, 2, null)) {
            rig.orchestrator.start(ImplementRequest.of(rig.plan));
            assertTrue(started.await(10, TimeUnit.SECONDS));
            var crashed = RunStateStore().load().orElseThrow();
            assertEquals("running", crashed.entry("S2").state());
            assertEquals("done", crashed.entry("S1").state());
            rig.orchestrator.stop();
            waitUntil(() -> !rig.orchestrator.active());
            assertTrue(rig.planFile().contains("- [x] S1.T1"));
            assertTrue(rig.planFile().contains("- [ ] S2.T1"));

            var second = new Brain().script("S2", simple("S2", "s2.txt", "2"));
            var again = rig.create(second, org.github.lscoughlin.jeorje.core.implement.RunStateStore.memory());
            var seeded = org.github.lscoughlin.jeorje.core.implement.RunStateStore.memory();
            seeded.save(crashed);
            again.close();
            var resumed = rig.create(second, seeded);
            var report = resumed.start(ImplementRequest.of(rig.plan)).get(60, TimeUnit.SECONDS);
            assertEquals("done", report.status(), report.render());
            assertTrue(second.of("S1").isEmpty(), "a finished story does not run again");
            var text = text(second.of("S2").get(0).messages().get(0));
            assertTrue(text.contains("### Previous attempt"), text);
            assertTrue(text.contains("interrupted"), text);
            assertEquals(2, report.stories().get(1).attempts());
            assertEquals("2", Files.readString(rig.root.resolve("s2.txt")));
            assertEquals("done", PlanParser.parse(rig.plan).status());
            resumed.close();
        }
    }

    // ---- approval and modes ----

    @Test
    void aDraftPlanNeedsApproval() throws Exception {
        var plan = planText("draft", null, Spec.of("S1", "First thing", "s1.txt"));
        var brain = new Brain().script("S1", simple("S1", "s1.txt", "one"));
        try (var rig = new Rig(tmp, true, plan, 1, brain, null, 2, null)) {
            var no = ImplementOrchestrator(rig, p -> false).run(ImplementRequest.of(rig.plan));
            assertEquals("not approved", no.status());
            assertEquals("draft", PlanParser.parse(rig.plan).status());
            assertTrue(brain.of("S1").isEmpty());
            var yes = ImplementOrchestrator(rig, p -> true).run(ImplementRequest.of(rig.plan));
            assertEquals("done", yes.status(), yes.render());
        }
    }

    @Test
    void yesApprovesWithoutAQuestion() throws Exception {
        var plan = planText("draft", null, Spec.of("S1", "First thing", "s1.txt"));
        var brain = new Brain().script("S1", simple("S1", "s1.txt", "one"));
        try (var rig = new Rig(tmp, true, plan, 1, brain, null, 2, null)) {
            var report = rig.orchestrator.run(ImplementRequest.of(rig.plan).withYes(true));
            assertEquals("done", report.status(), report.render());
        }
    }

    private static org.github.lscoughlin.jeorje.core.implement.ImplementOrchestrator ImplementOrchestrator(
            Rig rig, org.github.lscoughlin.jeorje.core.implement.Approver approver) {
        var settings = org.github.lscoughlin.jeorje.core.agent.AgentSettings.defaults("s1", rig.root, 100_000)
            .withSleeper(d -> { });
        var cfg = new org.github.lscoughlin.jeorje.core.config.JeorjeConfig.Implement(1, "", "none", 2, 200, true);
        return org.github.lscoughlin.jeorje.core.implement.ImplementOrchestrator.builder("s1", settings, m -> rig.brain, cfg)
            .store(rig.store).worktrees(rig.manager).approver(approver)
            .baseTools(baseTools()).workerTools(StoryTools.factory(List.of())).build();
    }

    @Test
    void workersShareTheDirectoryWithoutGit() throws Exception {
        var plan = planText("approved", "test -f s1.txt", Spec.of("S1", "First thing", "s1.txt"));
        var brain = new Brain().script("S1", simple("S1", "s1.txt", "one"));
        try (var rig = new Rig(tmp, false, plan, 1, brain, null, 2, null)) {
            var report = run(rig);
            assertEquals("done", report.status(), report.render());
            assertEquals("one", Files.readString(rig.root.resolve("s1.txt")));
            assertEquals("passed", report.verification());
        }
    }

    @Test
    void onlyNamedStoriesRunWithTheirOpenDependencies() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "One", "s1.txt"), Spec.of("S2", "Two", "s2.txt").after("S1"),
            Spec.of("S3", "Three", "s3.txt"));
        var brain = new Brain().script("S1", simple("S1", "s1.txt", "1")).script("S2", simple("S2", "s2.txt", "2"))
            .script("S3", simple("S3", "s3.txt", "3"));
        try (var rig = new Rig(tmp, true, plan, 2, brain, null, 2, null)) {
            var report = rig.orchestrator.start(ImplementRequest.of(rig.plan).withOnly(Set.of("S2")))
                .get(60, TimeUnit.SECONDS);
            assertEquals("done", report.status(), report.render());
            assertTrue(brain.of("S3").isEmpty());
            assertEquals(StoryState.DONE, state(report, "S1"));
            assertEquals(StoryState.DONE, state(report, "S2"));
            assertEquals("in-progress", PlanParser.parse(rig.plan).status());
        }
    }

    @Test
    void aPlanWithAnErrorStopsTheRun() throws Exception {
        var brain = new Brain();
        try (var rig = new Rig(tmp, true, "not a plan", 1, brain, null, 2, null)) {
            var report = run(rig);
            assertEquals("error", report.status());
            assertTrue(report.error().contains("front matter"), report.error());
        }
    }

    @Test
    void aFailingVerificationEndsTheAttemptAfterTheWorkerHadItsChancesToFixIt() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "First thing", "s1.txt").verifying("test -f never.txt"));
        var fix = new Response[] { call("f1", "sync_done", "{\"notes\":\"nothing to fix\"}"), Response.text("Fixed.") };
        var attempt = concat(simple("S1", "s1.txt", "one"), fix, fix);
        var brain = new Brain().script("S1", concat(attempt, attempt));
        try (var rig = new Rig(tmp, true, plan, 1, brain, null, 2, null)) {
            var report = run(rig);
            assertEquals("blocked", report.status(), report.render());
            var story = report.stories().get(0);
            assertEquals(StoryState.BLOCKED, story.state());
            assertEquals(2, story.attempts());
            assertTrue(story.reason().contains("The verification failed after the sync"), story.reason());
            assertFalse(Files.exists(rig.root.resolve("s1.txt")), "nothing was merged");
            var second = text(brain.of("S1").get(8).messages().get(0));
            assertTrue(second.contains("### Previous attempt") && second.contains("verification failed"), second);
        }
    }

    @Test
    void aRepairNeedsABlockedStoryWithAKeptBranch() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "First thing", "s1.txt"));
        var brain = new Brain().script("S1", simple("S1", "s1.txt", "one"));
        try (var rig = new Rig(tmp, true, plan, 1, brain, null, 2, null)) {
            assertThrows(IllegalStateException.class, () -> rig.orchestrator.repair("S1"));
            var report = run(rig);
            assertEquals("done", report.status());
            assertThrows(IllegalStateException.class, () -> rig.orchestrator.repair("S1"));
        }
    }

    @Test
    void storyToolsNeverAskAndTheAdvisorIsAnExtraTool() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "First thing", "s1.txt"));
        var brain = new Brain().script("S1", simple("S1", "s1.txt", "one"));
        try (var rig = new Rig(tmp, true, plan, 1, brain, null, 2, null)) {
            var settings = org.github.lscoughlin.jeorje.core.agent.AgentSettings.defaults("s1", rig.root, 100_000)
                .withSleeper(d -> { });
            var cfg = new org.github.lscoughlin.jeorje.core.config.JeorjeConfig.Implement(1, "", "none", 2, 200, true);
            var asking = (org.github.lscoughlin.jeorje.core.port.PermissionPolicy) a ->
                org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision.ASK;
            try (var other = org.github.lscoughlin.jeorje.core.implement.ImplementOrchestrator
                    .builder("s1", settings, m -> brain, cfg).store(rig.store).policy(asking)
                    .baseTools(baseTools())
                    .workerTools(StoryTools.factory(List.of(org.github.lscoughlin.jeorje.testkit.FakeTool.returning("advisor", "ok"))))
                    .build()) {
                assertTrue(other.workerToolNames().contains("advisor"));
                var policy = other.runtime().policyOf(org.github.lscoughlin.jeorje.core.implement.ImplementOrchestrator.ROOT)
                    .orElseThrow();
                var use = (java.util.function.Function<String, org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision>) tool ->
                    policy.check(new org.github.lscoughlin.jeorje.core.model.ToolUse("u", tool, null));
                assertEquals(org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision.ALLOW, use.apply("task_done"));
                assertEquals(org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision.ALLOW, use.apply("sync_abort"));
                assertEquals(org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision.ASK, use.apply("put_file"));
            }
        }
    }
}
