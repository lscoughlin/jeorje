package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepeatGuardTest {
    @TempDir Path dir;

    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger greps = new AtomicInteger();

    private Rig rig(Scripted llm, boolean guard) {
        var read = tool("read", true, (in, ctx) -> {
            reads.incrementAndGet();
            return ToolResultData.ok("content of " + in.path("path").asString());
        });
        var grep = tool("grep", true, (in, ctx) -> {
            greps.incrementAndGet();
            return ToolResultData.ok("matches");
        });
        var edit = tool("edit", false, (in, ctx) -> ToolResultData.ok("edited"));
        var settings = Rig.settings(dir).withRepeatGuard(guard);
        return new Rig(llm, policy(Decision.ALLOW), settings, read, grep, edit);
    }

    private static List<String> results(LlmRequest request) {
        var out = new ArrayList<String>();
        request.messages().forEach(m -> m.content().forEach(c -> {
            if (c instanceof ToolResult r) out.add(r.output());
        }));
        return out;
    }

    private void file(String name, String text) throws IOException {
        Files.writeString(dir.resolve(name), text);
    }

    @Test
    void aRepeatedReadDoesNotRunAndTheThirdRepeatSaysStop() throws IOException {
        file("A.java", "class A {}");
        var call = "{\"path\":\"A.java\"}";
        var llm = new Scripted(calls("c1", "read", call), calls("c2", "read", call), calls("c3", "read", call),
            calls("c4", "read", call), text("Done."));
        var rig = rig(llm, true);
        rig.agent.runTurn("look");
        assertEquals(1, reads.get(), "the tool ran once");
        var last = results(llm.requests.getLast());
        assertEquals("content of A.java", last.get(0));
        assertTrue(last.get(1).startsWith("Already done in turn 1: read {\"path\":\"A.java\"}. The result is in your "
            + "context or in the history. Use recall with the query \"A.java\" to read it again, or change the range."),
            last.get(1));
        assertFalse(last.get(1).contains("Stop reading"));
        assertFalse(last.get(2).contains("Stop reading"));
        assertTrue(last.get(3).endsWith("Stop reading. Use what you have, or write the plan now."), last.get(3));
    }

    @Test
    void anotherRangeOrAChangedFileRuns() throws IOException {
        file("A.java", "class A {}");
        var llm = new Scripted(calls("c1", "read", "{\"path\":\"A.java\"}"),
            calls("c2", "read", "{\"path\":\"A.java\",\"offset\":5}"), calls("c3", "read", "{\"path\":\"A.java\"}"),
            text("ok"), text("next"));
        var rig = rig(llm, true);
        rig.agent.runTurn("look");
        assertEquals(2, reads.get(), "the other range ran, the same call did not");
        file("A.java", "class A { int changed; }");
        var again = new Scripted(calls("d1", "read", "{\"path\":\"A.java\"}"), text("ok"));
        var rig2 = rig(again, true);
        rig2.agent.runTurn("a");
        var before = reads.get();
        file("A.java", "class A { int changed; int more; }");
        var llm3 = new Scripted(calls("e1", "read", "{\"path\":\"A.java\"}"), calls("e2", "read", "{\"path\":\"A.java\"}"),
            text("ok"));
        var rig3 = rig(llm3, true);
        rig3.agent.runTurn("a");
        assertEquals(before + 1, reads.get());
        // The file changes between two calls of one agent.
        var llm4 = new Scripted(calls("f1", "read", "{\"path\":\"A.java\"}"), text("one"),
            calls("f2", "read", "{\"path\":\"A.java\"}"), text("two"));
        var rig4 = rig(llm4, true);
        var start = reads.get();
        rig4.agent.runTurn("a");
        file("A.java", "class A { totally different and longer }");
        rig4.agent.runTurn("b");
        assertEquals(start + 2, reads.get(), "a changed file is read again");
    }

    @Test
    void aToolThatChangesTheWorkspaceClearsTheGuard() {
        var llm = new Scripted(calls("c1", "grep", "{\"pattern\":\"foo\"}"), calls("c2", "grep", "{\"pattern\":\"foo\"}"),
            calls("c3", "edit", "{}"), calls("c4", "grep", "{\"pattern\":\"foo\"}"), text("done"));
        var rig = rig(llm, true);
        rig.agent.runTurn("search");
        assertEquals(2, greps.get(), "the second call was a repeat, the call after the edit ran");
        var last = results(llm.requests.getLast());
        assertTrue(last.get(1).startsWith("Already done in turn 1: grep"), last.get(1));
        assertTrue(last.get(1).contains("query \"foo\""), last.get(1));
        assertEquals("matches", last.get(3));
    }

    @Test
    void theGuardRemembersAcrossTurnsAndNamesTheFirstTurn() throws IOException {
        file("B.java", "class B {}");
        var llm = new Scripted(calls("c1", "read", "{\"path\":\"B.java\"}"), text("one"),
            calls("c2", "read", "{\"path\":\"B.java\"}"), text("two"));
        var rig = rig(llm, true);
        rig.agent.runTurn("first");
        rig.agent.runTurn("second");
        assertEquals(1, reads.get());
        assertTrue(results(llm.requests.getLast()).getLast().startsWith("Already done in turn 1: read"));
    }

    @Test
    void theGuardIsOffUnlessTheSettingTurnsItOn() throws IOException {
        file("A.java", "class A {}");
        var call = "{\"path\":\"A.java\"}";
        var llm = new Scripted(calls("c1", "read", call), calls("c2", "read", call), text("done"));
        var rig = rig(llm, false);
        rig.agent.runTurn("look");
        assertEquals(2, reads.get());
        assertEquals("content of A.java", results(llm.requests.getLast()).get(1));
    }

    @Test
    void aFailedCallIsNotRemembered() {
        var llm = new Scripted(calls("c1", "bad", "{}"), calls("c2", "bad", "{}"), text("done"));
        var runs = new AtomicInteger();
        var bad = tool("read", true, (in, ctx) -> {
            runs.incrementAndGet();
            return ToolResultData.error("no such file");
        });
        var failing = new Scripted(calls("c1", "read", "{\"path\":\"missing\"}"),
            calls("c2", "read", "{\"path\":\"missing\"}"), text("done"));
        var rig = new Rig(failing, policy(Decision.ALLOW), Rig.settings(dir).withRepeatGuard(true), bad);
        rig.agent.runTurn("look");
        assertEquals(2, runs.get());
    }

    @Test
    void clearForgetsWhatWasSeen() throws Exception {
        file("A.java", "class A {}");
        var llm = new Scripted(calls("c1", "read", "{\"path\":\"A.java\"}"), text("one"),
            calls("c2", "read", "{\"path\":\"A.java\"}"), text("two"));
        var rig = rig(llm, true);
        rig.agent.runTurn("first");
        rig.agent.clear(1000);
        rig.agent.runTurn("second");
        assertEquals(2, reads.get());
    }
}
