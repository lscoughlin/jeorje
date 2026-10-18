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
        var first = firstUserText(last);
        assertTrue(first.contains("[Pinned items]") && first.contains("TODO-MARKER-ALPHA"), first);
        assertTrue(first.indexOf("TODO-MARKER-ALPHA") < first.indexOf("[Earlier context]"), "pinned above the history");
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
        var first = firstUserText(llm.requests.getLast());
        var pinned = first.substring(0, first.indexOf("[Earlier context]"));
        assertTrue(pinned.contains("LIST-2"), first);
        assertFalse(pinned.contains("LIST-1"), first);
    }

    @Test
    void anEmptyListAndAFailedCallAreNotPinned() {
        var empty = new Scripted(calls("c1", "todo", "{}"), text("a"), text("b"), text("c"));
        var rig = new Rig(empty, policy(Decision.ALLOW), settings(1), todoTool("Todo list: empty.", false));
        rig.agent.runTurn("one");
        rig.agent.runTurn("two");
        rig.agent.runTurn("three");
        assertFalse(firstUserText(empty.requests.getLast()).contains("[Pinned items]"));

        var failed = new Scripted(calls("c1", "todo", "{}"), text("a"), text("b"), text("c"));
        var rig2 = new Rig(failed, policy(Decision.ALLOW), settings(1), todoTool("More than one task is in_progress.", true));
        rig2.agent.runTurn("one");
        rig2.agent.runTurn("two");
        rig2.agent.runTurn("three");
        assertFalse(firstUserText(failed.requests.getLast()).contains("[Pinned items]"));
    }

    @Test
    void userPinsAndTheTodoListShareThePinnedSection() {
        var llm = new Scripted(calls("c1", "todo", "{}"), text("a"), text("b"));
        var rig = new Rig(llm, policy(Decision.ALLOW), settings(1), todoTool("Todo list:\n1. [ ] TODO-X", false));
        rig.agent.pin("USER-PIN");
        rig.agent.runTurn("one");
        rig.agent.runTurn("two");
        var first = firstUserText(llm.requests.getLast());
        assertTrue(first.indexOf("USER-PIN") < first.indexOf("TODO-X"), first);
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
            .filter(e -> "read".equals(e.chunk().toolName()) && e.chunk().part() > 0).findFirst().orElseThrow();
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
            .filter(e -> "read".equals(e.chunk().toolName()) && e.chunk().part() > 0).findFirst().orElseThrow();
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
        assertEquals(3, entries.size());
        assertEquals(Protection.PROTECTED, entries.get(0).protection(), "the first user instruction");
        assertEquals(Protection.CAREFUL, entries.get(1).protection(), "later turns are not instructions");
        assertEquals(Protection.CAREFUL, entries.get(2).protection());
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
