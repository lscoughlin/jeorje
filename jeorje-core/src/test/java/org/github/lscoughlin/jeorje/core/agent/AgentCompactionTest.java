package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.core.compaction.CompactionSettings;
import org.github.lscoughlin.jeorje.core.compaction.ContextCompactor;
import org.github.lscoughlin.jeorje.core.compaction.Protection;
import org.github.lscoughlin.jeorje.core.compaction.Pruner;
import org.github.lscoughlin.jeorje.core.compaction.TermStats;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The agent side of D5: classification at each compaction, immediate paths, the todo pin, and the triggers. */
class AgentCompactionTest {
    @TempDir Path dir;

    private static String bigText(String path) {
        var sb = new StringBuilder("// " + path + "\n");
        for (var i = 0; i < 250; i++) sb.append("line ").append(i).append(" of the big output with some words\n");
        return sb.toString();
    }

    private AgentSettings settings(int recent) {
        var d = AgentSettings.defaults("s1", dir, 100_000);
        return new AgentSettings(d.sessionId(), dir, 100_000, 60, 8192, recent, 100, d.retry(), null, "", d.sleeper());
    }

    private static ContextCompactor compactor(int budget, int recent, int dropAfter, int protectedTurns) {
        var pruner = new Pruner(null, null, TermStats.UNIFORM, Pruner.Config.defaults());
        return new ContextCompactor(pruner,
            new CompactionSettings(budget, 50, 80, recent, dropAfter, 20, 16_000, protectedTurns), new AgentId("a1"));
    }

    private static String firstUserText(LlmRequest request) {
        var first = request.messages().getFirst();
        var sb = new StringBuilder();
        for (var c : first.content()) if (c instanceof Text t) sb.append(t.text());
        return sb.toString();
    }

    private static String lastUserText(LlmRequest request) {
        var sb = new StringBuilder();
        for (var c : request.messages().getLast().content()) if (c instanceof Text t) sb.append(t.text());
        return sb.toString();
    }

    private static Tool todoTool(String output, boolean error) {
        return tool("todo", false, (in, ctx) -> error ? ToolResultData.error(output) : ToolResultData.ok(output));
    }

    @Test
    void theTodoListIsPinnedInEveryRequestAfterItsTurnLeavesTheRecentWindow() {
        var llm = new Scripted(calls("c1", "todo", "{}"), text("listed"), text("two"), text("three"), text("four"));
        var rig = new Rig(llm, policy(Decision.ALLOW), settings(1), todoTool("Todo list:\n1. [~] TODO-MARKER-ALPHA", false));
        rig.agent.withCompactor(compactor(1_000_000, 1, 12, 6));
        rig.agent.runTurn("plan");
        rig.agent.runTurn("second");
        rig.agent.runTurn("third");
        rig.agent.runTurn("fourth");
        var last = llm.requests.getLast();
        var tail = lastUserText(last);
        assertTrue(tail.contains("[Current state]") && tail.contains("TODO-MARKER-ALPHA"), tail);
        assertFalse(firstUserText(last).contains("[Pinned items]"), "the list is not pinned at the stable start");
        for (var i = 2; i < llm.requests.size(); i++) {
            assertTrue(ContextBuilderSupport.text(llm.requests.get(i)).contains("TODO-MARKER-ALPHA"), "request " + i);
        }
    }

    @Test
    void theLatestTodoListReplacesTheOlderOne() {
        var n = new int[] {0};
        var tool = tool("todo", false, (in, ctx) -> ToolResultData.ok("Todo list:\n1. [ ] LIST-" + (++n[0])));
        var llm = new Scripted(calls("c1", "todo", "{}"), text("a"), calls("c2", "todo", "{}"), text("b"), text("c"), text("d"));
        var rig = new Rig(llm, policy(Decision.ALLOW), settings(1), tool);
        rig.agent.runTurn("one");
        rig.agent.runTurn("two");
        rig.agent.runTurn("three");
        rig.agent.runTurn("four");
        var tail = lastUserText(llm.requests.getLast());
        assertTrue(tail.contains("LIST-2"), tail);
        assertFalse(tail.contains("LIST-1"), tail);
    }

    @Test
    void anEmptyListAndAFailedCallAreNotPinned() {
        var empty = new Scripted(calls("c1", "todo", "{}"), text("a"), text("b"), text("c"));
        var rig = new Rig(empty, policy(Decision.ALLOW), settings(1), todoTool("Todo list: empty.", false));
        rig.agent.runTurn("one");
        rig.agent.runTurn("two");
        rig.agent.runTurn("three");
        assertFalse(lastUserText(empty.requests.getLast()).contains("[Current state]"));

        var failed = new Scripted(calls("c1", "todo", "{}"), text("a"), text("b"), text("c"));
        var rig2 = new Rig(failed, policy(Decision.ALLOW), settings(1), todoTool("More than one task is in_progress.", true));
        rig2.agent.runTurn("one");
        rig2.agent.runTurn("two");
        rig2.agent.runTurn("three");
        assertFalse(lastUserText(failed.requests.getLast()).contains("[Current state]"));
    }

    @Test
    void userPinsStayAtTheStartAndTheTodoListMovesToTheEnd() {
        var llm = new Scripted(calls("c1", "todo", "{}"), text("a"), text("b"));
        var rig = new Rig(llm, policy(Decision.ALLOW), settings(1), todoTool("Todo list:\n1. [ ] TODO-X", false));
        rig.agent.pin("USER-PIN");
        rig.agent.runTurn("one");
        rig.agent.runTurn("two");
        var first = firstUserText(llm.requests.getLast());
        assertTrue(first.contains("USER-PIN") && !first.contains("TODO-X"), first);
        assertTrue(lastUserText(llm.requests.getLast()).contains("TODO-X"));
        assertEquals(List.of("USER-PIN"), rig.agent.pinnedTexts(), "the todo list is not a pin of the user");
    }

    private Scripted readScript(int turns, String path) {
        var steps = new ArrayList<Step>();
        for (var t = 1; t <= turns; t++) {
            if (t == 1) {
                steps.add(calls("c1", "read", "{\"path\":\"" + path + "\"}"));
            }
            steps.add(text("Done " + t));
        }
        return new Scripted(steps.toArray(new Step[0]));
    }

    private static Tool readTool(String path) {
        return tool("read", true, (in, ctx) -> ToolResultData.ok(bigText(path)));
    }

    @Test
    void thePruneListenerReceivesEachNewlyPrunedChunkOnce() {
        var path = "src/Kept.java";
        var llm = readScript(5, path);
        var rig = new Rig(llm, policy(Decision.ALLOW), settings(1), readTool(path));
        var seen = new ArrayList<org.github.lscoughlin.jeorje.core.model.Chunk>();
        var calls = new int[] {0};
        rig.agent.withCompactor(compactor(5_000, 1, 0, 6));
        rig.agent.withPruneListener(chunks -> {
            calls[0]++;
            assertFalse(chunks.isEmpty());
            seen.addAll(chunks);
        });
        for (var t = 1; t <= 5; t++) rig.agent.runTurn("turn " + t);
        var ids = seen.stream().map(org.github.lscoughlin.jeorje.core.model.Chunk::chunkId).toList();
        assertEquals(ids.size(), ids.stream().distinct().count(), "no chunk is reported twice: " + ids);
        assertTrue(calls[0] >= 1, "the listener ran");
        var prunedNow = rig.agent.workingEntries().stream().filter(e -> e.level() > 0)
            .map(e -> e.chunk().chunkId()).toList();
        assertEquals(prunedNow.stream().sorted().toList(), ids.stream().sorted().toList(),
            "the listener saw exactly the chunks that are pruned now");
        assertTrue(seen.stream().anyMatch(c -> "read".equals(c.toolName())), ids.toString());
    }

    @Test
    void immediatePathsStayInAChunkThatReachedLevelThree() {
        var path = "src/Kept.java";
        var llm = readScript(4, path);
        var rig = new Rig(llm, policy(Decision.ALLOW), settings(1), readTool(path));
        rig.agent.withCompactor(compactor(5_000, 1, 0, 6));
        for (var t = 1; t <= 4; t++) rig.agent.runTurn("turn " + t);
        var read = rig.agent.workingEntries().stream()
            .filter(e -> "read".equals(e.chunk().toolName()) && "tool_result".equals(e.chunk().kind())).findFirst().orElseThrow();
        assertEquals(3, read.level(), read.render());
        assertTrue(read.render().contains("files src/Kept.java"), read.render());
    }

    @Test
    void aPathOutsideTheProtectedTurnsIsNotKept() {
        var path = "src/Kept.java";
        var llm = readScript(4, path);
        var rig = new Rig(llm, policy(Decision.ALLOW), settings(1), readTool(path));
        rig.agent.withCompactor(compactor(5_000, 1, 0, 1));
        for (var t = 1; t <= 4; t++) rig.agent.runTurn("turn " + t);
        var read = rig.agent.workingEntries().stream()
            .filter(e -> "read".equals(e.chunk().toolName()) && "tool_result".equals(e.chunk().kind())).findFirst().orElseThrow();
        assertEquals(3, read.level());
        assertFalse(read.render().contains("files "), read.render());
    }

    @Test
    void chunksAreClassifiedAgainAtEachCompaction() {
        var llm = new Scripted(text("a"), text("b"), text("c"), text("d"));
        var rig = new Rig(llm, policy(Decision.ALLOW), settings(1));
        rig.agent.withCompactor(compactor(1_000_000, 1, 12, 6));
        for (var t = 1; t <= 4; t++) rig.agent.runTurn("turn " + t);
        var entries = rig.agent.workingEntries();
        assertEquals(6, entries.size());
        for (var e : entries) {
            var expected = e.chunk().kind().equals("user") ? Protection.PROTECTED : Protection.CAREFUL;
            assertEquals(expected, e.protection(), e.chunk().chunkId() + " " + e.chunk().kind());
        }
        assertEquals(3, entries.stream().filter(e -> e.protection() == Protection.PROTECTED).count(),
            "the user text of each turn is an instruction");
    }

    @Test
    void aSearchThatALaterEditUsedIsPrunedAtTheFirstCompaction() {
        var grep = bigText("src/Foo.java");
        var llm = new Scripted(calls("c1", "grep", "{}"), calls("c2", "edit", "{\"path\":\"src/Foo.java\"}"), text("Done 1"),
            text("Done 2"), text("Done 3"));
        var rig = new Rig(llm, policy(Decision.ALLOW), settings(1),
            tool("grep", true, (in, ctx) -> ToolResultData.ok(grep)),
            tool("edit", false, (in, ctx) -> ToolResultData.ok("edited")));
        rig.agent.withCompactor(compactor(5_000, 1, 12, 6));
        for (var t = 1; t <= 3; t++) rig.agent.runTurn("turn " + t);
        var search = rig.agent.workingEntries().stream()
            .filter(e -> "grep".equals(e.chunk().toolName()) && e.chunk().part() > 0).findFirst().orElseThrow();
        assertEquals(3, search.level(), "a concluded search does not wait for the second age limit");
    }

    @Test
    void aSearchWithoutALaterUseKeepsTheNormalAgeLimit() {
        var grep = bigText("src/Foo.java");
        var llm = new Scripted(calls("c1", "grep", "{}"), text("Done 1"), text("Done 2"), text("Done 3"));
        var rig = new Rig(llm, policy(Decision.ALLOW), settings(1), tool("grep", true, (in, ctx) -> ToolResultData.ok(grep)));
        rig.agent.withCompactor(compactor(5_000, 1, 12, 6));
        for (var t = 1; t <= 3; t++) rig.agent.runTurn("turn " + t);
        var search = rig.agent.workingEntries().stream()
            .filter(e -> "grep".equals(e.chunk().toolName()) && e.chunk().part() > 0).findFirst().orElseThrow();
        assertEquals(2, search.level());
    }

    @Test
    void theTriggersMeasureTheWholeWorkingContext() {
        var path = "src/Whole.java";
        var llm = new Scripted(calls("c1", "read", "{\"path\":\"" + path + "\"}"), text("Done 1"),
            calls("c2", "read", "{\"path\":\"" + path + "\"}"), text("Done 2"), text("Done 3"));
        var rig = new Rig(llm, policy(Decision.ALLOW), settings(1), readTool(path));
        // The big result is about 2,750 tokens. The soft limit is 3,000, so one old result alone is under it.
        assertTrue(bigText(path).length() / 4 < 3_000);
        rig.agent.withCompactor(compactor(6_000, 1, 12, 6));
        rig.agent.runTurn("one");
        rig.agent.runTurn("two");
        rig.agent.runTurn("three");
        var event = rig.events(AgentEvent.Compacted.class);
        assertFalse(event.isEmpty(), "the system prompt, the tools, and the recent turn count too");
        assertTrue(event.getLast().tokensFreed() > 0);
    }

    /** Settings with a chosen context window, so that a small budget can overflow. */
    private AgentSettings windowSettings(int window, int recent) {
        var d = AgentSettings.defaults("s1", dir, window);
        return new AgentSettings(d.sessionId(), dir, window, 60, 8192, recent, 100, d.retry(), null, "",
            d.sleeper());
    }

    /** The tool results of a request, in order. */
    private static List<org.github.lscoughlin.jeorje.core.model.ToolResult> toolResults(LlmRequest request) {
        var out = new ArrayList<org.github.lscoughlin.jeorje.core.model.ToolResult>();
        request.messages().forEach(m -> m.content().forEach(c -> {
            if (c instanceof org.github.lscoughlin.jeorje.core.model.ToolResult r) out.add(r);
        }));
        return out;
    }

    /** A text of about 40,000 characters, which is about 10,000 tokens. */
    private static String hugeText() {
        var sb = new StringBuilder();
        for (var i = 0; sb.length() < 40_000; i++) {
            sb.append("line ").append(i).append(" of the huge output with some words of the file\n");
        }
        return sb.toString();
    }

    @Test
    void aTurnThatOverflowsOnlyThroughItsOwnToolResultsIsSpilledInForcedMode() {
        // Seven results are fewer than the minimum count of the normal spill, and the live tail holds the six newest,
        // so only the first result can leave. It is huge, and the turn alone pushes the request over the soft budget.
        var steps = new ArrayList<Step>();
        steps.add(calls("c1", "huge", "{}", "c2", "small", "{}", "c3", "small", "{}", "c4", "small", "{}",
            "c5", "small", "{}", "c6", "small", "{}", "c7", "small", "{}"));
        steps.add(text("Done."));
        var llm = new Scripted(steps.toArray(new Step[0]));
        var window = 20_000;
        var rig = new Rig(llm, policy(Decision.ALLOW), windowSettings(window, 6),
            tool("huge", true, (in, ctx) -> ToolResultData.ok(hugeText())),
            tool("small", true, (in, ctx) -> ToolResultData.ok("small output")));
        var budget = new ContextBuilder(window, 60, 8192).budget();
        rig.agent.runTurn("read the files");
        assertEquals(2, llm.requests.size());
        var last = llm.requests.getLast();
        var results = toolResults(last);
        assertEquals(7, results.size(), "the turn is below the minimum count of the normal spill");
        assertTrue(7 < TurnSpill.MIN_RESULTS);
        assertTrue(ContextBuilder.rawEstimate(last) <= budget,
            ContextBuilder.rawEstimate(last) + " > " + budget);
        assertTrue(results.getFirst().output().startsWith(TurnSpill.MARK), "the oldest result is a stub");
        for (var i = 1; i < results.size(); i++) {
            assertFalse(results.get(i).output().startsWith(TurnSpill.MARK), "the live tail stays whole, result " + i);
        }
        assertTrue(ContextBuilder.rawEstimate(last) <= budget,
            ContextBuilder.rawEstimate(last) + " > " + budget);
        var event = rig.events(AgentEvent.Compacted.class);
        assertFalse(event.isEmpty(), "the user sees the forced spill");
        assertEquals(1, event.getLast().chunksPruned());
        assertTrue(event.getLast().tokensFreed() > 5_000);
        assertTrue(rig.store.chunks.stream().anyMatch(c -> c.part() >= TurnSpill.FIRST_PART),
            "the full text is in the store");
    }

    @Test
    void aTurnUnderTheSoftBudgetIsNotSpilledInForcedMode() {
        var steps = new ArrayList<Step>();
        steps.add(calls("c1", "huge", "{}", "c2", "small", "{}", "c3", "small", "{}", "c4", "small", "{}",
            "c5", "small", "{}", "c6", "small", "{}", "c7", "small", "{}"));
        steps.add(text("Done."));
        var llm = new Scripted(steps.toArray(new Step[0]));
        var window = 200_000;
        var rig = new Rig(llm, policy(Decision.ALLOW), windowSettings(window, 6),
            tool("huge", true, (in, ctx) -> ToolResultData.ok(hugeText())),
            tool("small", true, (in, ctx) -> ToolResultData.ok("small output")));
        rig.agent.runTurn("read the file");
        assertTrue(toolResults(llm.requests.getLast()).stream()
            .noneMatch(r -> r.output().startsWith(TurnSpill.MARK)), "a turn in the budget keeps every result");
        assertTrue(rig.events(AgentEvent.Compacted.class).isEmpty());
        assertTrue(rig.store.chunks.stream().noneMatch(c -> c.part() >= TurnSpill.FIRST_PART));
    }

    /** Reads the whole text of a request. */
    private static final class ContextBuilderSupport {
        static String text(LlmRequest request) {
            var sb = new StringBuilder();
            for (var m : request.messages()) {
                for (var c : m.content()) {
                    if (c instanceof Text t) sb.append(t.text());
                    else if (c instanceof org.github.lscoughlin.jeorje.core.model.ToolResult r) sb.append(r.output());
                }
            }
            return sb.toString();
        }
    }
}
