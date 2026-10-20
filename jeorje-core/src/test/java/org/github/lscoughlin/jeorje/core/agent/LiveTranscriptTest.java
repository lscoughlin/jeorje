package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.github.lscoughlin.jeorje.core.session.TranscriptReader;
import org.github.lscoughlin.jeorje.core.session.TranscriptWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The transcript has the messages of a turn while the turn runs, and each message has one row. */
class LiveTranscriptTest {
    @TempDir Path dir;

    private List<String> rows(Path file) throws IOException {
        return Files.readAllLines(file);
    }

    private Agent agent(Scripted llm, TranscriptWriter writer, org.github.lscoughlin.jeorje.core.port.Tool... tools) {
        return new Agent(new AgentId("a1"), Rig.settings(dir), llm, List.of(tools), policy(Decision.ALLOW),
            new ListStore(), writer, e -> { }, CLOCK);
    }

    @Test
    void aRunningTurnShowsItsEarlierMessagesAndTheEndHasOneRowForEach() throws IOException {
        var file = dir.resolve("transcript.jsonl");
        var seen = new ArrayList<Long>();
        try (var writer = new TranscriptWriter(file)) {
            var llm = new Scripted(calls("c1", "probe", "{}"), calls("c2", "probe", "{}"), calls("c3", "probe", "{}"),
                text("done"));
            var probe = tool("probe", true, (in, ctx) -> {
                try {
                    seen.add(rows(file).stream().filter(r -> r.contains("\"type\":\"message\"")).count());
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
                return ToolResultData.ok("ok");
            });
            agent(llm, writer, probe).runTurn("start");
        }
        // The user message and the assistant message of the call are in the file when the first tool runs.
        assertEquals(List.of(2L, 4L, 6L), seen);
        var all = rows(file);
        assertEquals(8L, all.stream().filter(r -> r.contains("\"type\":\"message\"")).count(),
            "user, 3 x (assistant + result), final text");
        assertEquals(1L, all.stream().filter(r -> r.contains("\"type\":\"turn_end\"")).count());
        var turns = TranscriptReader.read(file);
        assertEquals(1, turns.size());
        assertEquals(8, turns.getFirst().turn().messages().size());
    }

    @Test
    void aFileThatEndsInTheMiddleOfATurnLoadsTheEarlierTurnsOnly() throws IOException {
        var file = dir.resolve("transcript.jsonl");
        try (var writer = new TranscriptWriter(file)) {
            agent(new Scripted(text("first answer")), writer).runTurn("one");
            var llm = new Scripted(calls("c1", "stop", "{}"), text("never"));
            var stop = tool("stop", true, (in, ctx) -> {
                throw new IllegalStateException("the process stops here");
            });
            try {
                agent(llm, writer, stop).runTurn("two");
            } catch (RuntimeException ignored) {
                // The tool error becomes a result, so the turn may end normally. The test needs only the file.
            }
        }
        var text = String.join("\n", rows(file));
        assertTrue(text.contains("\"type\":\"turn_end\""));
        // Cut the file after the first row of turn 2, as a crash would.
        var cut = new ArrayList<String>();
        var ends = 0;
        for (var row : rows(file)) {
            if (row.contains("\"type\":\"turn_end\"") && ++ends == 2) break;
            cut.add(row);
        }
        Files.write(file, cut);
        var turns = TranscriptReader.read(file);
        assertEquals(1, turns.size());
        assertEquals(1, turns.getFirst().turn().number());
    }

    @Test
    void aTurnThatRestartsAfterACrashDoesNotKeepTheOldRows() throws IOException {
        var file = dir.resolve("transcript.jsonl");
        try (var first = new TranscriptWriter(file)) {
            first.writeMessage(new AgentId("a1"), 1, org.github.lscoughlin.jeorje.core.model.Message.user("old"),
                CLOCK.instant());
            first.writeMessage(new AgentId("a1"), 1, org.github.lscoughlin.jeorje.core.model.Message.user("old 2"),
                CLOCK.instant());
        }
        try (var second = new TranscriptWriter(file)) {
            agent(new Scripted(text("fresh")), second).runTurn("again");
        }
        var turns = TranscriptReader.read(file);
        assertEquals(1, turns.size());
        assertEquals(2, turns.getFirst().turn().messages().size(), "the new user message and the reply only");
    }
}
