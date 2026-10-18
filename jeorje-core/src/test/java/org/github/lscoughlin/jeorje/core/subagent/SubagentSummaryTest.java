package org.github.lscoughlin.jeorje.core.subagent;

import static org.github.lscoughlin.jeorje.core.subagent.SubagentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.github.lscoughlin.jeorje.core.agent.AgentInput;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubagentSummaryTest {
    @TempDir Path dir;

    private static String longSummary() {
        return VALID.replace("Done: Wrote the parser.", "Done: " + "word ".repeat(320));
    }

    @Test
    void waitModeDeliversTheSummaryAndWritesTheChunk() throws Exception {
        try (var rig = new Rig(dir)) {
            rig.model("m", new Scripted(text(WORK_TEXT), text(VALID)));
            var id = rig.spawn("worker", "m");
            var completion = rig.await(id);
            assertEquals(Completion.Outcome.COMPLETED, completion.outcome());
            assertEquals(VALID, completion.summary());
            assertEquals(WORK_TEXT, completion.finalText());
            assertEquals(List.of(AgentLifecycle.RUNNING, AgentLifecycle.COMPLETED), rig.states(id));
            var chunk = rig.store.chunks.stream().filter(c -> c.kind().equals("subagent_summary")).findFirst()
                .orElseThrow();
            assertEquals("root", chunk.agentId());
            assertEquals(7, chunk.turn());
            assertEquals(id.value(), chunk.sourceAgentId());
            assertFalse(chunk.text().contains("sourceAgentId"), chunk.text());
            assertTrue(chunk.text().contains("Keywords: Parser.java, regex, parser"));
            assertTrue(chunk.part() >= 1000, "summary parts do not collide with turn parts");
        }
    }

    @Test
    void backgroundModeDeliversAnInputEventToTheParent() throws Exception {
        try (var rig = new Rig(dir)) {
            rig.model("m", new Scripted(text(WORK_TEXT), text(VALID)));
            var id = rig.spawn("worker", "m");
            waitUntil(() -> !rig.rootInput.isEmpty());
            var input = assertInstanceOf(AgentInput.SubagentResult.class, rig.rootInput.getFirst());
            assertEquals(id, input.from());
            assertEquals(VALID, input.text().substring(input.text().indexOf('\n') + 1));
        }
    }

    @Test
    void waitModeDoesNotSendAnInputEvent() throws Exception {
        try (var rig = new Rig(dir)) {
            rig.model("m", new Scripted(text(WORK_TEXT), text(VALID)));
            var id = rig.runtime.spawn(rig.root, SpawnRequest.of("worker", "Fix Parser.java regex.")
                .withModel("m").withMode(SpawnMode.WAIT));
            rig.await(id);
            assertTrue(rig.rootInput.isEmpty());
        }
    }

    @Test
    void invalidSummaryIsAskedAgainOnce() throws Exception {
        try (var rig = new Rig(dir)) {
            var llm = new Scripted(text(WORK_TEXT), text("Outcome: completed\nDone: x"), text(VALID));
            rig.model("m", llm);
            var completion = rig.await(rig.spawn("worker", "m"));
            assertEquals(VALID, completion.summary());
            assertEquals(3, llm.calls());
            assertTrue(llm.requestText(2).contains("The part 'Files' is missing."), llm.requestText(2));
            assertTrue(llm.requests.get(2).tools().isEmpty(), "the summary step has no tools");
        }
    }

    @Test
    void twoInvalidSummariesGiveTheFallback() throws Exception {
        try (var rig = new Rig(dir)) {
            var llm = new Scripted(text(WORK_TEXT), text("bad"), text("still bad"));
            rig.model("m", llm);
            var completion = rig.await(rig.spawn("worker", "m"));
            assertEquals(3, llm.calls());
            assertEquals(Completion.Outcome.COMPLETED, completion.outcome());
            assertTrue(completion.summary().startsWith("Outcome: completed"), completion.summary());
            assertTrue(completion.summary().contains(WORK_TEXT));
            assertTrue(SummaryCheck.words(completion.summary()) <= 300);
        }
    }

    @Test
    void keywordsThatAreNotInTheHistoryAreRejected() throws Exception {
        try (var rig = new Rig(dir)) {
            var invented = VALID.replace("Keywords: Parser.java, regex, parser",
                "Keywords: Parser.java, regex, InventedClassName");
            var llm = new Scripted(text(WORK_TEXT), text(invented), text(invented));
            rig.model("m", llm);
            var completion = rig.await(rig.spawn("worker", "m"));
            assertTrue(llm.requestText(2).contains("'InventedClassName' is not in the history"));
            assertFalse(completion.summary().contains("InventedClassName"), "the fallback is used");
        }
    }

    @Test
    void longSummaryIsRejected() throws Exception {
        try (var rig = new Rig(dir)) {
            var llm = new Scripted(text(WORK_TEXT), text(longSummary()), text(VALID));
            rig.model("m", llm);
            var completion = rig.await(rig.spawn("worker", "m"));
            assertEquals(VALID, completion.summary());
            assertTrue(llm.requestText(2).contains("The limit is 300"));
        }
    }

    @Test
    void wrongOutcomeIsRejected() {
        var problems = SummaryCheck.validate(VALID.replace("Outcome: completed", "Outcome: failed"), "completed",
            HistoryText.of(List.of()));
        assertTrue(problems.stream().anyMatch(p -> p.contains("must start with 'completed'")), problems.toString());
    }

    @Test
    void failedSubagentGetsTheSummaryFromTheCompactor() throws Exception {
        var failedSummary = VALID.replace("Outcome: completed", "Outcome: failed: model error");
        var compactor = new Scripted(text(failedSummary));
        try (var rig = new Rig(dir, SubagentLimits.defaults(), null, compactor, List.of(),
            a -> PermissionPolicy.Decision.ALLOW)) {
            var llm = new Scripted(fail(400));
            rig.model("m", llm);
            var completion = rig.await(rig.spawn("worker", "m"));
            assertEquals(Completion.Outcome.FAILED, completion.outcome());
            assertTrue(completion.reason().contains("Model call failed"), completion.reason());
            assertEquals(failedSummary, completion.summary());
            assertEquals(1, llm.calls(), "the failed subagent writes no summary itself");
            assertEquals(List.of(AgentLifecycle.RUNNING, AgentLifecycle.FAILED), rig.states(completion.agent()));
        }
    }

    @Test
    void failedSubagentWithoutCompactorGetsTheFallback() throws Exception {
        try (var rig = new Rig(dir)) {
            rig.model("m", new Scripted(fail(400)));
            var completion = rig.await(rig.spawn("worker", "m"));
            assertEquals(Completion.Outcome.FAILED, completion.outcome());
            var parts = SummaryCheck.parts(completion.summary());
            assertEquals(SummaryCheck.LABELS.size(), parts.size(), completion.summary());
            assertTrue(parts.get("Outcome").startsWith("failed: Model call failed"), parts.get("Outcome"));
            assertTrue(SummaryCheck.words(completion.summary()) <= 300);
        }
    }

    @Test
    void invalidCompactorSummaryFallsBack() throws Exception {
        var compactor = new Scripted(text("nonsense"), text("more nonsense"));
        try (var rig = new Rig(dir, SubagentLimits.defaults(), null, compactor, List.of(),
            a -> PermissionPolicy.Decision.ALLOW)) {
            rig.model("m", new Scripted(fail(400)));
            var completion = rig.await(rig.spawn("worker", "m"));
            assertEquals(2, compactor.calls(), "one retry");
            assertTrue(completion.summary().startsWith("Outcome: failed"));
        }
    }

    @Test
    void cancelledSubagentGetsASummary() throws Exception {
        try (var rig = new Rig(dir)) {
            var emitted = new CountDownLatch(1);
            rig.model("m", new Scripted(hang(emitted)));
            var id = rig.spawn("worker", "m");
            assertTrue(emitted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(rig.runtime.cancel("worker"));
            var completion = rig.await(id);
            assertEquals(Completion.Outcome.CANCELLED, completion.outcome());
            assertTrue(completion.summary().startsWith("Outcome: cancelled"), completion.summary());
            assertEquals(AgentLifecycle.CANCELLED, rig.runtime.info(id).orElseThrow().state());
        }
    }

    @Test
    void fallbackListsChangedFilesAndAlwaysPassesTheCheck() throws Exception {
        try (var rig = new Rig(dir)) {
            rig.model("m", new Scripted(call("c1", "write", "{\"path\":\"src/Parser.java\"}"),
                text(WORK_TEXT), text("bad"), text("bad")));
            var id = rig.runtime.spawn(rig.root, SpawnRequest.of("worker", "Write src/Parser.java").withModel("m"));
            // The parent has no write tool, so the call is denied. The path is still in the history.
            var completion = rig.await(id);
            var parts = SummaryCheck.parts(completion.summary());
            assertEquals("src/Parser.java", parts.get("Files"));
            assertTrue(SummaryCheck.keywords(parts.get("Keywords")).size() >= 3);
        }
    }
}
