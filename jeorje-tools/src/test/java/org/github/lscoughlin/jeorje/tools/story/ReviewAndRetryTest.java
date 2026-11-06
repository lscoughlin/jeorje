package org.github.lscoughlin.jeorje.tools.story;

import static org.github.lscoughlin.jeorje.tools.story.StoryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.jeorje.core.implement.AdvisorReviewer;
import org.github.lscoughlin.jeorje.core.implement.ImplementEvent;
import org.github.lscoughlin.jeorje.core.implement.ImplementReport;
import org.github.lscoughlin.jeorje.core.implement.ImplementRequest;
import org.github.lscoughlin.jeorje.core.implement.Reviewer;
import org.github.lscoughlin.jeorje.core.implement.RunStateStore;
import org.github.lscoughlin.jeorje.core.implement.StoryState;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The review that gives no answer, and the next attempt that starts from the work of a failed attempt. */
class ReviewAndRetryTest {
    @TempDir Path tmp;

    private static final Reviewer.Request REQUEST =
        new Reviewer.Request("S1", "## Story S1: One", "DRAFT", List.of("a.txt"), "+line");

    private static ImplementReport run(Rig rig) throws Exception {
        return rig.orchestrator.start(ImplementRequest.of(rig.plan)).get(60, TimeUnit.SECONDS);
    }

    private static Response[] concat(Response[]... parts) {
        var all = new ArrayList<Response>();
        for (var p : parts) all.addAll(List.of(p));
        return all.toArray(new Response[0]);
    }

    private static Response[] shell(String id, String command) {
        return new Response[] { call(id, "shell", "{\"command\":\"" + command + "\"}") };
    }

    /** Attempt that reads files first, then does the normal steps of a story. */
    private static Response[] finish(String story) {
        var task = story + ".T1";
        return new Response[] {
            call(story + "-d1", "task_done", "{\"taskId\":\"" + task + "\",\"note\":\"fixed\"}"),
            call(story + "-d2", "story_done", "{\"summary\":\"DRAFT-" + story + " fixed\",\"filesChanged\":[]}"),
            Response.text("Finished " + story + ".")
        };
    }

    private static Reviewer rejectOnce(List<String> problems, AtomicInteger calls) {
        return request -> calls.getAndIncrement() == 0 ? Reviewer.Verdict.reject(problems) : Reviewer.Verdict.approve();
    }

    private static List<String> branches(Rig rig) {
        return g(rig.root, "branch", "--list", "jeorje/*").lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    /** The output limit of each request that the client got. */
    private static List<Integer> limits(ScriptedLlmClient client) {
        return client.requests().stream().map(LlmRequest::maxOutputTokens).toList();
    }

    // ---- the reviewer ----

    @Test
    void anEmptyAnswerIsAskedAgainAfterTwoSeconds() {
        var client = new ScriptedLlmClient(Response.text(""), Response.text("approve"));
        var waits = new ArrayList<Duration>();
        var verdict = new AdvisorReviewer(client, waits::add).review(REQUEST);
        assertTrue(verdict.approved());
        assertEquals(2, client.callCount());
        assertEquals(List.of(Duration.ofSeconds(2)), waits);
    }

    @Test
    void threeEmptyAnswersGiveNoReview() {
        var client = new ScriptedLlmClient(Response.text(""), Response.text("  \n"), Response.text(""));
        var waits = new ArrayList<Duration>();
        var verdict = new AdvisorReviewer(client, waits::add).review(REQUEST);
        assertTrue(verdict.unavailable());
        assertFalse(verdict.approved());
        assertEquals("The review gave no answer.", verdict.reason());
        assertEquals(3, client.callCount());
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(5)), waits);
    }

    @Test
    void aBlankAnswerThatIsNotAtTheOutputLimitKeepsTheBaseLimit() {
        var client = new ScriptedLlmClient(Response.text(""), Response.text("  \n"), Response.text(""));
        var waits = new ArrayList<Duration>();
        var verdict = new AdvisorReviewer(client, waits::add).review(REQUEST);
        assertTrue(verdict.unavailable());
        assertEquals(3, client.callCount());
        assertEquals(List.of(2048, 2048, 2048), limits(client));
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(5)), waits);
    }

    @Test
    void aBlankAnswerAtTheOutputLimitIsAskedAgainWithALargerLimit() {
        var client = new ScriptedLlmClient(Response.outputLimit(), Response.text("approve"));
        var waits = new ArrayList<Duration>();
        var verdict = new AdvisorReviewer(client, waits::add).review(REQUEST);
        assertTrue(verdict.approved(), verdict.reason());
        assertEquals(2, client.callCount());
        assertEquals(List.of(2048, 8192), limits(client));
        assertEquals(List.of(Duration.ofSeconds(2)), waits);
    }

    @Test
    void threeBlankAnswersAtTheOutputLimitGiveNoReviewAndKeepTheLargerLimit() {
        var client = new ScriptedLlmClient(Response.outputLimit(), Response.outputLimit(), Response.outputLimit());
        var waits = new ArrayList<Duration>();
        var verdict = new AdvisorReviewer(client, waits::add).review(REQUEST);
        assertTrue(verdict.unavailable());
        assertEquals("The review gave no answer.", verdict.reason());
        assertEquals(3, client.callCount());
        assertEquals(List.of(2048, 8192, 8192), limits(client));
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(5)), waits);
    }

    @Test
    void theReviewUsesTheGivenOutputLimits() {
        var client = new ScriptedLlmClient(Response.outputLimit(), Response.text("approve"));
        var verdict = new AdvisorReviewer(client, d -> { }, 1000, 4000).review(REQUEST);
        assertTrue(verdict.approved(), verdict.reason());
        assertEquals(List.of(1000, 4000), limits(client));
    }

    @Test
    void aFailedCallIsAskedAgain() {
        var client = new ScriptedLlmClient(Response.failure(500), Response.text("approve"));
        var verdict = new AdvisorReviewer(client, d -> { }).review(REQUEST);
        assertTrue(verdict.approved());
        assertEquals(2, client.callCount());
    }

    @Test
    void threeFailedCallsGiveNoReviewWithTheCause() {
        var client = new ScriptedLlmClient(Response.failure(500), Response.failure(500), Response.failure(500));
        var verdict = new AdvisorReviewer(client, d -> { }).review(REQUEST);
        assertTrue(verdict.unavailable());
        assertTrue(verdict.reason().startsWith("The review call failed"), verdict.reason());
    }

    @Test
    void anAnswerWithProblemsIsAFailedReviewAndIsNotAskedAgain() {
        var client = new ScriptedLlmClient(Response.text("- Criterion 2 fails.\n- No test."));
        var waits = new ArrayList<Duration>();
        var verdict = new AdvisorReviewer(client, waits::add).review(REQUEST);
        assertEquals(Reviewer.Verdict.Kind.REJECTED, verdict.kind());
        assertEquals(List.of("Criterion 2 fails.", "No test."), verdict.problems());
        assertEquals(1, client.callCount());
        assertTrue(waits.isEmpty());
        assertTrue(new AdvisorReviewer(new ScriptedLlmClient(Response.text("approve")), waits::add)
            .review(REQUEST).approved());
    }

    // ---- no review ----

    @Test
    void aStoryWithAnEmptyReviewIsMergedWithANoticeAndNoFailedAttempt() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "First thing", "s1.txt"));
        var client = new ScriptedLlmClient(Response.text(""), Response.text(""), Response.text(""));
        var brain = new Brain().script("S1", simple("S1", "s1.txt", "one"));
        try (var rig = new Rig(tmp, true, plan, 1, brain, new AdvisorReviewer(client, d -> { }), 2, null)) {
            var report = run(rig);
            assertEquals("done", report.status(), report.render());
            var story = report.stories().get(0);
            assertEquals(StoryState.DONE, story.state());
            assertEquals(1, story.attempts());
            assertTrue(story.result().contains("The review gave no answer."), story.result());
            assertEquals("one", Files.readString(rig.root.resolve("s1.txt")));
            assertEquals(3, client.callCount());
            assertTrue(rig.events.stream().anyMatch(e -> e instanceof ImplementEvent.Notice n
                && n.text().contains("The review of S1 is not available")));
            assertTrue(rig.events.stream().noneMatch(e -> e instanceof ImplementEvent.Reviewed));
            assertTrue(branches(rig).isEmpty());
        }
    }

    @Test
    void anEmptyAnswerThatTheRetryFixesIsAnApproval() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "First thing", "s1.txt"));
        var client = new ScriptedLlmClient(Response.text(""), Response.text("approve"));
        var brain = new Brain().script("S1", simple("S1", "s1.txt", "one"));
        try (var rig = new Rig(tmp, true, plan, 1, brain, new AdvisorReviewer(client, d -> { }), 2, null)) {
            var report = run(rig);
            assertEquals("done", report.status(), report.render());
            assertEquals(1, report.stories().get(0).attempts());
            assertFalse(report.stories().get(0).result().contains("no answer"));
            assertEquals(List.of(true), rig.events.stream().filter(e -> e instanceof ImplementEvent.Reviewed)
                .map(e -> ((ImplementEvent.Reviewed) e).approved()).toList());
        }
    }

    // ---- the next attempt keeps the work ----

    @Test
    void afterAFailedReviewTheNextAttemptSeesTheFilesAndTheFeedback() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "First thing", "s1.txt"));
        var calls = new AtomicInteger();
        var reviewer = rejectOnce(List.of("The X handling is missing.", "Name the file Y."), calls);
        var brain = new Brain().script("S1", concat(simple("S1", "s1.txt", "v1"),
            shell("r1", "cat s1.txt; git branch --list 'jeorje/*'"), finish("S1")));
        try (var rig = new Rig(tmp, true, plan, 1, brain, reviewer, 2, null)) {
            var report = run(rig);
            assertEquals("done", report.status(), report.render());
            assertEquals(2, report.stories().get(0).attempts());
            var task = text(brain.of("S1").get(4).messages().get(0));
            assertTrue(task.contains("### Review feedback"), task);
            assertTrue(task.contains("- The X handling is missing.\n- Name the file Y."), task);
            assertTrue(task.contains("The files of the previous attempt are in your worktree."), task);
            assertTrue(task.contains("### Previous attempt"), task);
            var seen = toolResults(brain.of("S1").get(5)).get(0);
            assertTrue(seen.contains("v1"), "the worker sees the file of attempt 1: " + seen);
            assertTrue(seen.contains("jeorje/demo/s1"), "the branch of attempt 1 stays during attempt 2: " + seen);
            assertEquals("v1", Files.readString(rig.root.resolve("s1.txt")));
            assertTrue(branches(rig).isEmpty(), branches(rig).toString());
            assertFalse(Files.exists(rig.root.resolve(".jeorje/worktrees/demo/s1")));
            assertFalse(Files.exists(rig.root.resolve(".jeorje/worktrees/demo/s1-a2")));
            assertTrue(rig.events.stream().anyMatch(e -> e instanceof ImplementEvent.Notice n
                && n.text().contains("starts from the branch")));
        }
    }

    @Test
    void afterAFailedVerificationTheNextAttemptSeesTheFiles() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "First thing", "s1.txt").verifying("grep -q v2 s1.txt"));
        var fix = new Response[] { call("f1", "sync_done", "{\"notes\":\"nothing to fix\"}"), Response.text("Fixed.") };
        var attempt1 = concat(simple("S1", "s1.txt", "v1"), fix, fix);
        var attempt2 = concat(shell("r1", "cat s1.txt"), simple("S1", "s1.txt", "v2"));
        var brain = new Brain().script("S1", concat(attempt1, attempt2));
        try (var rig = new Rig(tmp, true, plan, 1, brain, null, 2, null)) {
            var report = run(rig);
            assertEquals("done", report.status(), report.render());
            assertEquals(2, report.stories().get(0).attempts());
            var task = text(brain.of("S1").get(8).messages().get(0));
            assertTrue(task.contains("Failing check: grep -q v2 s1.txt"), task);
            assertTrue(task.contains("The files of the previous attempt are in your worktree."), task);
            assertFalse(task.contains("### Review feedback"), task);
            assertTrue(toolResults(brain.of("S1").get(9)).get(0).contains("v1"));
            assertEquals("v2", Files.readString(rig.root.resolve("s1.txt")));
            assertTrue(branches(rig).isEmpty(), branches(rig).toString());
        }
    }

    @Test
    void afterABlockedCallTheNextAttemptSeesTheUncommittedFiles() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "First thing", "s1.txt"));
        var attempt1 = new Response[] {
            call("p1", "put_file", "{\"path\":\"a.txt\",\"content\":\"half\"}"),
            call("b1", "story_blocked", "{\"reason\":\"I am stuck\"}"), Response.text("Stopped.") };
        var brain = new Brain().script("S1", concat(attempt1, shell("r1", "cat a.txt"), simple("S1", "s1.txt", "one")));
        try (var rig = new Rig(tmp, true, plan, 1, brain, null, 2, null)) {
            var report = run(rig);
            assertEquals("done", report.status(), report.render());
            assertTrue(toolResults(brain.of("S1").get(4)).get(0).contains("half"));
            assertEquals("half", Files.readString(rig.root.resolve("a.txt")));
        }
    }

    @Test
    void withRetryFromSourceTheNextAttemptStartsClean() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "First thing", "s1.txt"));
        var calls = new AtomicInteger();
        var reviewer = rejectOnce(List.of("The X handling is missing."), calls);
        var brain = new Brain().script("S1", concat(simple("S1", "s1.txt", "v1"),
            shell("r1", "ls; git branch --list 'jeorje/*'"), simple("S1", "s1.txt", "v2")));
        try (var rig = new Rig(tmp, true, plan, 1, brain, reviewer, 2, null)) {
            rig.orchestrator.close();
            rig.orchestrator = rig.create(brain, RunStateStore.file(rig.stateFile), "source");
            var report = run(rig);
            assertEquals("done", report.status(), report.render());
            var task = text(brain.of("S1").get(4).messages().get(0));
            assertTrue(task.contains("### Review feedback"), "the feedback stays: " + task);
            assertFalse(task.contains("in your worktree"), task);
            var seen = toolResults(brain.of("S1").get(5)).get(0);
            assertFalse(seen.contains("s1.txt"), "attempt 2 starts from the source: " + seen);
            assertFalse(seen.contains("jeorje/demo/s1\n"), seen);
            assertEquals("v2", Files.readString(rig.root.resolve("s1.txt")));
            assertTrue(branches(rig).isEmpty());
        }
    }

    @Test
    void aFailedReviewCountsAsAnAttemptSoMaxAttemptsStopsTheLoop() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "First thing", "s1.txt"));
        var calls = new AtomicInteger();
        Reviewer reviewer = request -> {
            calls.incrementAndGet();
            return Reviewer.Verdict.reject(List.of("Still wrong."));
        };
        var brain = new Brain().script("S1", concat(simple("S1", "s1.txt", "v1"), simple("S1", "s1.txt", "v2")));
        try (var rig = new Rig(tmp, true, plan, 1, brain, reviewer, 2, null)) {
            var report = run(rig);
            assertEquals("blocked", report.status(), report.render());
            var story = report.stories().get(0);
            assertEquals(StoryState.BLOCKED, story.state());
            assertEquals(2, story.attempts());
            assertEquals(2, calls.get());
            assertTrue(story.reason().contains("Review rejected: Still wrong."), story.reason());
            assertFalse(Files.exists(rig.root.resolve("s1.txt")), "nothing was merged");
            assertEquals(List.of("jeorje/demo/s1-a2"), branches(rig).stream()
                .map(b -> b.replace("+ ", "").replace("* ", "").strip()).toList(),
                "the branch of attempt 1 is gone, the branch of the last attempt stays");
        }
    }

    @Test
    void aPassingVerificationOfTheWorkerGivesItANewBudget() throws Exception {
        var plan = planText("approved", null, Spec.of("S1", "First thing", "s1.txt").verifying("true"));
        var holder = new java.util.concurrent.atomic.AtomicReference<Rig>();
        var before = new AtomicInteger(-99);
        var after = new AtomicInteger(-99);
        org.github.lscoughlin.jeorje.core.worktree.Verifier verifier = (dir, command) -> {
            before.set(holder.get().orchestrator.turnUsage().orElseThrow().used());
            return new org.github.lscoughlin.jeorje.core.worktree.Verifier.Result(true, "");
        };
        Reviewer reviewer = request -> {
            after.set(holder.get().orchestrator.turnUsage().orElseThrow().used());
            return Reviewer.Verdict.approve();
        };
        var brain = new Brain().script("S1", simple("S1", "s1.txt", "one"));
        try (var rig = new Rig(tmp, true, plan, 1, brain, reviewer, 2, null)) {
            holder.set(rig);
            rig.verifier = verifier;
            rig.orchestrator.close();
            rig.orchestrator = rig.create(brain, RunStateStore.file(rig.stateFile));
            var report = run(rig);
            assertEquals("done", report.status(), report.render());
            assertTrue(before.get() >= 1, "the worker made calls after its last task_done: " + before.get());
            assertEquals(0, after.get(), "the passing verification reset the count");
        }
    }
}
