package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.Turn;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.github.lscoughlin.jeorje.core.session.TurnChunker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TurnSpillTest {
    @TempDir Path dir;

    /** A result of about 12,000 characters, with a name that is different for each call. */
    private static String big(String name) {
        var sb = new StringBuilder();
        for (var i = 0; sb.length() < 12_000; i++) {
            sb.append("line ").append(i).append(" of ").append(name).append(" with the class ").append(name)
                .append("Service and some more words\n");
        }
        return sb.toString();
    }

    private static List<ToolResult> toolResults(LlmRequest request) {
        var out = new ArrayList<ToolResult>();
        request.messages().forEach(m -> m.content().forEach(c -> {
            if (c instanceof ToolResult r) out.add(r);
        }));
        return out;
    }

    private Scripted thirtyReads(int count) {
        var steps = new ArrayList<Step>();
        for (var i = 1; i <= count; i++) steps.add(calls("c" + i, "bigread", "{\"name\":\"File" + i + "\"}"));
        steps.add(text("Done."));
        return new Scripted(steps.toArray(new Step[0]));
    }

    private Rig rig(Scripted llm, org.github.lscoughlin.jeorje.core.port.Tool... extra) {
        var tools = new ArrayList<org.github.lscoughlin.jeorje.core.port.Tool>();
        tools.add(tool("bigread", true, (in, ctx) -> ToolResultData.ok(big(in.get("name").asString()))));
        tools.addAll(List.of(extra));
        return new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir), tools.toArray(new org.github.lscoughlin.jeorje.core.port.Tool[0]));
    }

    @Test
    void everyRequestStaysInTheBudgetAndOldResultsBecomeStubs() {
        var llm = thirtyReads(30);
        var rig = rig(llm);
        var budget = new ContextBuilder(100_000, 60, 8192).budget();
        rig.agent.runTurn("read everything");
        assertEquals(31, llm.requests.size());
        for (var request : llm.requests) {
            assertTrue(ContextBuilder.rawEstimate(request) <= budget, ContextBuilder.rawEstimate(request) + " > " + budget);
        }
        var last = toolResults(llm.requests.getLast());
        assertEquals(30, last.size());
        for (var r : last.subList(last.size() - TurnSpill.LIVE_TAIL, last.size())) {
            assertFalse(r.output().startsWith(TurnSpill.MARK), "the live tail stays whole");
            assertTrue(r.output().length() > 12_000);
        }
        var stubs = last.stream().filter(r -> r.output().startsWith(TurnSpill.MARK)).toList();
        assertTrue(stubs.size() >= 10, "stubs: " + stubs.size());
        var stub = stubs.getFirst().output();
        assertTrue(stub.contains("| recall: ") && stub.contains(", id=a1:1.10"), stub);
        assertTrue(stub.contains("bigread {\"name\":\"File"), stub);
        assertTrue(stub.lines().count() <= 7, stub);
        assertFalse(rig.events(AgentEvent.Compacted.class).isEmpty());
    }

    @Test
    void theFullTextIsInTheStoreAndInTheTranscript() {
        var llm = thirtyReads(30);
        var rig = rig(llm);
        rig.agent.runTurn("read everything");
        var stub = toolResults(llm.requests.getLast()).stream().filter(r -> r.output().startsWith(TurnSpill.MARK))
            .findFirst().orElseThrow().output();
        var id = stub.substring(stub.indexOf("id=") + 3, stub.indexOf(']'));
        var chunk = rig.store.get(id).orElseThrow();
        assertEquals("tool_result", chunk.kind());
        assertEquals("bigread", chunk.toolName());
        assertEquals(1, chunk.turn());
        assertTrue(chunk.part() >= TurnSpill.FIRST_PART);
        assertTrue(chunk.text().contains("File1Service"), "the first result is the biggest by age");
        var transcript = rig.transcript.getFirst();
        var results = transcript.messages().stream().flatMap(m -> m.content().stream())
            .filter(ToolResult.class::isInstance).map(ToolResult.class::cast).toList();
        assertEquals(30, results.size());
        assertTrue(results.stream().noneMatch(r -> r.output().startsWith(TurnSpill.MARK)));
        assertTrue(results.stream().allMatch(r -> r.output().length() >= 12_000));
    }

    @Test
    void spillChunksNeverClashWithTheFinalChunksOfTheTurn() {
        var llm = thirtyReads(30);
        var rig = rig(llm);
        rig.agent.runTurn("read everything");
        var ids = rig.store.chunks.stream().map(c -> c.chunkId()).toList();
        assertEquals(ids.size(), ids.stream().distinct().count(), "ids are unique");
        assertTrue(rig.store.chunks.stream().anyMatch(c -> c.part() >= TurnSpill.FIRST_PART));
        assertTrue(rig.store.chunks.stream().anyMatch(c -> c.part() < TurnSpill.FIRST_PART));
        var main = rig.store.chunks.stream().filter(c -> c.part() < TurnSpill.FIRST_PART)
            .map(c -> c.text()).reduce("", (a, b) -> a + b);
        assertTrue(main.contains("[spilled: bigread"), "the final chunks hold the stubs");
        assertFalse(main.contains("line 50 of File1 "), "and not the full text a second time");
    }

    @Test
    void todoAndAskUserAndSkillResultsAreNeverSpilled() {
        var steps = new ArrayList<Step>();
        steps.add(calls("t1", "todo", "{}"));
        steps.add(calls("a1", "ask_user", "{}"));
        for (var i = 1; i <= 20; i++) steps.add(calls("c" + i, "bigread", "{\"name\":\"File" + i + "\"}"));
        steps.add(text("Done."));
        var llm = new Scripted(steps.toArray(new Step[0]));
        var todo = tool("todo", false, (in, ctx) -> ToolResultData.ok(big("Todo")));
        var ask = tool("ask_user", false, (in, ctx) -> ToolResultData.ok("User answer: " + big("Answer")));
        var rig = rig(llm, todo, ask);
        rig.agent.runTurn("go");
        var last = toolResults(llm.requests.getLast());
        assertFalse(last.get(0).output().startsWith(TurnSpill.MARK), "todo");
        assertFalse(last.get(1).output().startsWith(TurnSpill.MARK), "ask_user");
        assertTrue(last.stream().anyMatch(r -> r.output().startsWith(TurnSpill.MARK)));
    }

    @Test
    void aShortTurnIsNotChanged() {
        var llm = thirtyReads(5);
        var rig = rig(llm);
        rig.agent.runTurn("read a little");
        assertTrue(toolResults(llm.requests.getLast()).stream().noneMatch(r -> r.output().startsWith(TurnSpill.MARK)));
        assertTrue(rig.events(AgentEvent.Compacted.class).isEmpty());
        assertTrue(rig.store.chunks.stream().noneMatch(c -> c.part() >= TurnSpill.FIRST_PART));
    }

    @Test
    void theNextTurnSeesTheStubsAndAResumeSeesTheFullTranscript() {
        var llm = thirtyReads(30);
        var rig = rig(llm);
        rig.agent.runTurn("read everything");
        Turn live = rig.agent.turns().getFirst();
        var liveResults = live.messages().stream().flatMap(m -> m.content().stream())
            .filter(ToolResult.class::isInstance).map(ToolResult.class::cast).toList();
        assertTrue(liveResults.stream().anyMatch(r -> r.output().startsWith(TurnSpill.MARK)));
        // The chunker accepts both forms of the turn.
        var chunks = TurnChunker.forFormat("s1", 2).chunk(rig.transcript.getFirst(), CLOCK.instant());
        assertTrue(chunks.stream().anyMatch(c -> c.kind().equals("tool_result") && c.text().contains("File1Service")),
            "the full text of the first result is chunked again");
    }

    @Test
    void keywordsHaveThePathOfTheCallAndTheFrequentIdentifiers() {
        var use = new org.github.lscoughlin.jeorje.core.model.ToolUse("u", "read",
            MAPPER.readTree("{\"path\":\"src/main/Foo.java\"}"));
        var kw = TurnSpill.keywords(use, big("Widget"));
        assertEquals("src/main/Foo.java", kw.getFirst());
        assertTrue(kw.contains("WidgetService"), kw.toString());
        assertTrue(kw.size() <= 6);
    }
}
