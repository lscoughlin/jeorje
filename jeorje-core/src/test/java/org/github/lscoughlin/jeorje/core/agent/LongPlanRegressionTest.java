package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.Step;
import org.github.lscoughlin.jeorje.core.compaction.CompactionSettings;
import org.github.lscoughlin.jeorje.core.compaction.ContextCompactor;
import org.github.lscoughlin.jeorje.core.compaction.Pruner;
import org.github.lscoughlin.jeorje.core.compaction.TermStats;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.plan.PlanMode;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The failed planning session, in a test. Turn 1 reads 30 files of 12,000 characters and asks the user a question.
 * Turn 2 tries to read the first 10 files again, recalls, and submits the plan. The context window is 128,000
 * tokens, so the budget is 76,800 tokens.
 */
class LongPlanRegressionTest {
    @TempDir Path dir;

    private static String content(int n) {
        var sb = new StringBuilder();
        for (var i = 0; sb.length() < 12_000; i++) {
            sb.append("line ").append(i).append(" of File").append(n).append(" uses the KeyHandler").append(n)
                .append(" class and its fields\n");
        }
        return sb.toString();
    }

    private static List<ToolResult> results(LlmRequest request) {
        var out = new ArrayList<ToolResult>();
        request.messages().forEach(m -> m.content().forEach(c -> {
            if (c instanceof ToolResult r) out.add(r);
        }));
        return out;
    }

    @Test
    void theAgentKeepsItsAnswersDoesNotReadTwiceAndSubmitsThePlan() throws IOException {
        for (var i = 1; i <= 30; i++) Files.writeString(dir.resolve("F" + i + ".java"), content(i));
        var steps = new ArrayList<Step>();
        for (var i = 1; i <= 30; i++) steps.add(calls("r" + i, "read", "{\"path\":\"F" + i + ".java\"}"));
        steps.add(calls("q1", "ask_user", "{\"question\":\"Which defaults?\"}"));
        steps.add(text("I have asked the question."));
        for (var i = 1; i <= 10; i++) steps.add(calls("s" + i, "read", "{\"path\":\"F" + i + ".java\"}"));
        steps.add(calls("rc", "recall", "{\"query\":\"Which defaults?\"}"));
        steps.add(calls("p1", "plan_submit", "{}"));
        steps.add(text("The plan is written."));
        var llm = new Scripted(steps.toArray(new Step[0]));

        var read = tool("read", true, (in, ctx) -> {
            try {
                return ToolResultData.ok(Files.readString(dir.resolve(in.get("path").asString())));
            } catch (IOException e) {
                return ToolResultData.error(e.getMessage());
            }
        });
        var ask = tool("ask_user", false, (in, ctx) -> ToolResultData.ok("User answer: sqlite for every platform"));
        var recall = tool("recall", true, (in, ctx) -> ToolResultData.ok("recall-ref: {}\nmain:1.99 | user"));
        var submit = tool("plan_submit", false, (in, ctx) -> {
            try {
                Files.writeString(dir.resolve("plan.md"), "# Plan");
            } catch (IOException e) {
                return ToolResultData.error(e.getMessage());
            }
            return ToolResultData.ok("Plan written: plan.md");
        });
        var settings = AgentSettings.defaults("s1", dir, 128_000).withRepeatGuard(true);
        var rig = new Rig(llm, policy(Decision.ALLOW), settings, read, ask, recall, submit);
        var pruner = new Pruner(null, null, TermStats.UNIFORM, Pruner.Config.defaults());
        rig.agent.withCompactor(new ContextCompactor(pruner, new CompactionSettings(76_800, 50, 80, 6, 12, 20, 16_000),
            new AgentId("a1")));
        var plan = new PlanMode().maxExploreCalls(40);
        plan.enter("make a plan");
        rig.agent.withToolFilter(plan::refuse).withStepBoundary(plan::notes);

        rig.agent.runTurn("make a plan for the key handling");
        rig.agent.runTurn("Add the text keys and the Cmd modifier");

        var budget = new ContextBuilder(128_000, 60, 8192).budget();
        assertEquals(76_800, budget);
        for (var request : llm.requests) {
            assertTrue(ContextBuilder.rawEstimate(request) <= budget, ContextBuilder.rawEstimate(request) + " > " + budget);
        }
        for (var usage : rig.events(AgentEvent.ContextUsage.class)) {
            assertTrue(usage.tokens() <= usage.budget(), "the status line stays below 100%: " + usage);
        }

        // The answer of turn 1 is pinned in every request of turn 2.
        var turn2 = llm.requests.subList(32, llm.requests.size());
        assertEquals(13, turn2.size());
        for (var request : turn2) {
            var first = request.messages().getFirst().content().getFirst().toString();
            assertTrue(first.contains("Decisions of the user:\n- Which defaults?: sqlite for every platform"),
                first.substring(0, Math.min(300, first.length())));
        }

        // The repeated reads do not run again.
        var last = results(llm.requests.getLast());
        var repeats = last.stream().filter(r -> r.output().startsWith("Already done in turn 1: read")).toList();
        assertEquals(10, repeats.size());
        assertTrue(repeats.getLast().output().contains("Stop reading."));

        // The plan exists, and the transcript has the full text of every result of turn 1.
        assertTrue(Files.isRegularFile(dir.resolve("plan.md")));
        var transcript = rig.transcript.getFirst().messages().stream().flatMap(m -> m.content().stream())
            .filter(ToolResult.class::isInstance).map(ToolResult.class::cast)
            .filter(r -> r.output().startsWith("line 0 of File") || r.output().startsWith("[spilled")).toList();
        assertEquals(30, transcript.size());
        assertTrue(transcript.stream().allMatch(r -> r.output().startsWith("line 0 of File") && r.output().length() >= 12_000),
            "no stub in the transcript");

        // Turn 1 was spilled, and the spilled text is in the store.
        assertTrue(rig.events(AgentEvent.Compacted.class).size() > 0, "results of turn 1 were spilled");
        assertTrue(rig.store.chunks.stream().anyMatch(c -> c.part() >= TurnSpill.FIRST_PART));
        var allResults = rig.agent.turns().stream().mapToLong(t -> t.messages().stream()
            .flatMap(m -> m.content().stream()).filter(ToolResult.class::isInstance).count()).sum();
        assertEquals(30 + 1 + 10 + 1 + 1, allResults, "30 reads and a question, 10 reads, a recall, and the plan");
    }
}
