package org.github.lscoughlin.jeorje.core.directory;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Turn;
import org.github.lscoughlin.jeorje.core.session.TranscriptWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentDirectoryTest {
    private static final AgentId ROOT = new AgentId("root");
    private static final AgentId KID = new AgentId("kid");

    @Test
    void inMemoryTracksOutputStateAndSteering() {
        var dir = new InMemoryAgentDirectory()
                .add(new AgentEntry(ROOT, "root", null, "RUNNING", null, "main", ""))
                .add(new AgentEntry(KID, "kid", ROOT, "IDLE", "peer1", null, null));
        dir.appendOutput(KID, "one");
        dir.appendOutput(KID, "two");
        dir.setState(KID, "RUNNING");
        dir.steer(KID, "go");
        assertEquals(List.of("one", "two"), dir.history(KID));
        assertEquals("two", dir.agents().get(1).lastOutput());
        assertEquals("RUNNING", dir.agents().get(1).state());
        assertEquals(List.of(new InMemoryAgentDirectory.Steering(KID, "go")), dir.steeringSent());
    }

    @Test
    void theDefaultTreeIsEmptyAndTheInMemoryTreeFollowsTheParents() {
        AgentDirectory plain = new AgentDirectory() {
            @Override public List<AgentEntry> agents() { return List.of(); }

            @Override public List<String> history(AgentId agent) { return List.of(); }

            @Override public void steer(AgentId agent, String text) { }
        };
        assertTrue(plain.tree().isEmpty());
        var dir = new InMemoryAgentDirectory()
                .add(new AgentEntry(ROOT, "root", null, "RUNNING", null, "main", ""))
                .add(new AgentEntry(KID, "kid", ROOT, "IDLE", null, null, null));
        var harness = dir.tree().getFirst();
        assertEquals(RosterKind.HARNESS, harness.kind());
        var root = harness.children().getFirst();
        assertEquals(RosterKind.AGENT, root.kind());
        assertEquals("root", root.id());
        assertEquals(RosterKind.SUBAGENT, root.children().getFirst().kind());
        assertEquals("kid", root.children().getFirst().id());
        assertTrue(root.hasChildren());
        assertEquals("", new RosterNode(RosterKind.AGENT, "a", "a", null, null).detail());
    }

    @Test
    void transcriptHistoryReadsOneAgentInOrder(@TempDir Path tmp) {
        var file = tmp.resolve("transcript.jsonl");
        try (var w = new TranscriptWriter(file)) {
            w.writeTurn(new Turn(KID, 1, List.of(Message.user("q"), Message.assistant("a"))), Instant.now());
            w.writeTurn(new Turn(ROOT, 1, List.of(Message.user("other"))), Instant.now());
            w.writeTurn(new Turn(KID, 2, List.of(Message.assistant("b"))), Instant.now());
        }
        assertEquals(List.of("> q", "a", "b"), TranscriptHistory.read(file, KID));
    }
}
