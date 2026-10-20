package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Notes;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.plan.PlanMode;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class PlanExploreTest {
    @TempDir Path dir;

    private static ToolUse use(String tool) {
        return new ToolUse("id", tool, JsonMapper.builder().build().createObjectNode());
    }

    @Test
    void theModeCountsCallsAndNotesComeOncePerStep() {
        var plan = new PlanMode().maxExploreCalls(20);
        assertTrue(plan.refuse(use("read")).isEmpty(), "not active yet");
        assertEquals(0, plan.exploreCalls());
        plan.enter("goal");
        for (var i = 0; i < 14; i++) plan.refuse(use("read"));
        assertTrue(plan.notes().isEmpty(), "14 of 20 is below 75%");
        plan.refuse(use("grep"));
        var soft = plan.notes();
        assertEquals(1, soft.size());
        assertEquals(Notes.of("You made 15 tool calls. Write the plan soon. If a fact is open, ask the user with "
            + "ask_user."), soft.getFirst());
        assertTrue(plan.notes().isEmpty(), "once");
        for (var i = 0; i < 5; i++) plan.refuse(use("bash"));
        var hard = plan.notes();
        assertEquals(1, hard.size());
        assertTrue(hard.getFirst().startsWith(Notes.PREFIX + "You reached the limit of 20 tool calls for exploring."));
        assertTrue(hard.getFirst().contains("After 10 more calls, only plan_submit, ask_user, and recall work."));
        assertTrue(plan.notes().isEmpty());
    }

    @Test
    void todoAndAdvisorAreNotCountedAndQuestionsAndPlansStartAgain() {
        var plan = new PlanMode().maxExploreCalls(10);
        plan.enter("goal");
        plan.refuse(use("todo"));
        plan.refuse(use("advisor"));
        assertEquals(0, plan.exploreCalls());
        for (var i = 0; i < 6; i++) plan.refuse(use("read"));
        assertEquals(6, plan.exploreCalls());
        plan.refuse(use("ask_user"));
        assertEquals(0, plan.exploreCalls());
        for (var i = 0; i < 9; i++) plan.refuse(use("read"));
        plan.refuse(use(PlanMode.PLAN_SUBMIT));
        assertEquals(0, plan.exploreCalls());
        plan.refuse(use("read"));
        plan.resetExploration();
        assertEquals(0, plan.exploreCalls());
    }

    @Test
    void afterTheGraceCallsOnlyThreeToolsWork() {
        var plan = new PlanMode().maxExploreCalls(5);
        plan.enter("goal");
        for (var i = 0; i < 15; i++) assertTrue(plan.refuse(use("read")).isEmpty(), "call " + (i + 1));
        var refusal = plan.refuse(use("read"));
        assertEquals("Plan mode refused read: you made 16 tool calls without a plan. Call plan_submit now with what "
            + "you know, or ask the user with ask_user.", refusal.orElseThrow());
        assertTrue(plan.refuse(use("bash")).isPresent());
        assertTrue(plan.refuse(use("recall")).isEmpty(), "recall still works");
        assertTrue(plan.refuse(use("ask_user")).isEmpty());
        assertTrue(plan.refuse(use("read")).isEmpty(), "a question starts the count again");
    }

    @Test
    void theModeIsOffOutsidePlanMode() {
        var plan = new PlanMode().maxExploreCalls(5);
        for (var i = 0; i < 30; i++) assertTrue(plan.refuse(use("read")).isEmpty());
        assertTrue(plan.notes().isEmpty());
        assertTrue(plan.promptSection().contains("You have 5 tool calls to explore before you must write the plan."));
    }

    private static List<String> texts(LlmRequest request) {
        var out = new ArrayList<String>();
        request.messages().forEach(m -> m.content().forEach(c -> {
            if (c instanceof Text t) out.add(t.text());
        }));
        return out;
    }

    @Test
    void anAgentGetsTheNotesTheRefusalAndAFreshStartAfterPlanSubmit() {
        var plan = new PlanMode().maxExploreCalls(8);
        plan.enter("goal");
        var steps = new ArrayList<Step>();
        for (var i = 1; i <= 19; i++) steps.add(calls("c" + i, "look", "{\"n\":" + i + "}"));
        steps.add(calls("p1", "plan_submit", "{}"));
        steps.add(calls("c20", "look", "{\"n\":20}"));
        steps.add(text("Done."));
        var llm = new Scripted(steps.toArray(new Step[0]));
        var ran = new AtomicInteger();
        var look = tool("look", true, (in, ctx) -> {
            ran.incrementAndGet();
            return ToolResultData.ok("seen " + in.get("n"));
        });
        var submit = tool("plan_submit", false, (in, ctx) -> ToolResultData.ok("Plan written."));
        var rig = new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir), look, submit);
        rig.agent.withToolFilter(plan::refuse).withStepBoundary(() -> plan.notes());
        rig.agent.runTurn("make a plan");

        var all = new ArrayList<List<String>>();
        llm.requests.forEach(r -> all.add(texts(r)));
        var firstSoft = -1;
        var firstHard = -1;
        for (var i = 0; i < all.size(); i++) {
            if (firstSoft < 0 && all.get(i).stream().anyMatch(t -> t.contains("Write the plan soon"))) firstSoft = i;
            if (firstHard < 0 && all.get(i).stream().anyMatch(t -> t.contains("You reached the limit of 8"))) firstHard = i;
        }
        assertEquals(6, firstSoft, "75% of 8 is 6 calls: the request after the sixth call has the note");
        assertEquals(8, firstHard);
        assertEquals(1, all.getLast().stream().filter(t -> t.contains("Write the plan soon")).count(), "once");
        var last = llm.requests.getLast();
        var results = new ArrayList<String>();
        last.messages().forEach(m -> m.content().forEach(c -> {
            if (c instanceof ToolResult r) results.add(r.output());
        }));
        assertEquals(8 + 10 + 1, ran.get(), "the limit, the grace calls, and the call after the plan ran");
        assertTrue(results.get(18).startsWith("Plan mode refused look: you made 19 tool calls"), results.get(18));
        assertEquals("Plan written.", results.get(19));
        assertEquals("seen 20", results.get(20), "plan_submit starts the count again");
        assertEquals(1, plan.exploreCalls(), "one call after the plan");
    }
}
