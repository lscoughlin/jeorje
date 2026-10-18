package org.github.lscoughlin.jeorje.core.session;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.model.Turn;
import org.github.lscoughlin.jeorje.core.port.Filters;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;
import org.github.lscoughlin.jeorje.core.port.Syntax;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class SessionTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AgentId agent = new AgentId("main");

    private Turn plain(int n, String user, String assistant) {
        return new Turn(agent, n, List.of(Message.user(user), Message.assistant(assistant)));
    }

    private Turn toolTurn(int n, String tool, String input, String output) {
        return new Turn(agent, n, List.of(Message.user("go"),
            new Message(Role.ASSISTANT, List.of(new ToolUse("u" + n, tool, JSON.readTree(input)))),
            new Message(Role.USER, List.of(new ToolResult("u" + n, output, false)))));
    }

    // ---- chunker

    @Test
    void oneChunkPerTurn() {
        var chunks = new TurnChunker("s1").chunk(plain(1, "hello", "world"), T0);
        assertEquals(1, chunks.size());
        var c = chunks.getFirst();
        assertEquals("main:1.0", c.chunkId());
        assertEquals("mixed", c.kind());
        assertEquals("s1", c.sessionId());
        assertTrue(c.text().contains("hello") && c.text().contains("world"));
    }

    @Test
    void smallToolResultStaysInTurnChunk() {
        var chunks = new TurnChunker("s1").chunk(toolTurn(2, "read", "{\"path\":\"a\"}", "x".repeat(8000)), T0);
        assertEquals(1, chunks.size());
        assertEquals("read", chunks.getFirst().toolName());
    }

    @Test
    void largeResultSplitsWithOverlapAtParagraphBoundaries() {
        var para = ("word ".repeat(100)).strip();
        var big = String.join("\n\n", java.util.Collections.nCopies(60, para)); // about 30,000 chars
        var chunks = new TurnChunker("s1").chunk(toolTurn(3, "read", "{}", big), T0);
        var parts = chunks.stream().filter(c -> c.kind().equals("tool_result")).toList();
        assertTrue(parts.size() >= 4);
        for (var p : parts) {
            assertTrue(p.text().length() <= 8000);
            assertEquals("read", p.toolName());
        }
        for (var i = 0; i + 1 < parts.size(); i++) {
            var a = parts.get(i).text();
            var b = parts.get(i + 1).text();
            assertTrue(a.endsWith("\n\n"), "split at paragraph boundary");
            var tail = a.substring(a.length() - 100);
            assertTrue(b.contains(tail) || a.contains(b.substring(0, 100)), "pieces overlap");
            assertTrue(big.contains(a));
        }
        assertEquals(chunks.size(), chunks.stream().map(c -> c.part()).distinct().count());
    }

    @Test
    void splitFallsBackToLinesAndHardCut() {
        var chunker = new TurnChunker("s", 100);
        var lines = String.join("\n", java.util.Collections.nCopies(30, "0123456789".repeat(1))); // no blank lines
        var pieces = chunker.split(lines);
        assertTrue(pieces.size() > 3);
        assertTrue(pieces.stream().allMatch(p -> p.length() <= 100));
        assertTrue(pieces.getFirst().endsWith("\n"));
        var hard = chunker.split("a".repeat(350));
        assertTrue(hard.stream().allMatch(p -> p.length() <= 100));
        // overlap is 10 chars: piece 2 starts with the last 10 chars of piece 1
        assertEquals(hard.getFirst().length() - 10, 90);
        var covered = hard.getFirst().length() + hard.stream().skip(1).mapToInt(p -> p.length() - 10).sum();
        assertEquals(350, covered);
    }

    @Test
    void recallChunkHoldsOnlyReferences() {
        var recalled = "SECRET-RECALLED-TEXT see main:2.0 and main:5.1";
        var turn = toolTurn(7, "recall", "{\"query\":\"build error\",\"filters\":{\"kinds\":[\"tool_result\"]}}", recalled);
        var chunks = new TurnChunker("s1").chunk(turn, T0);
        var recall = chunks.stream().filter(c -> "recall".equals(c.toolName())).findFirst().orElseThrow();
        assertTrue(recall.text().contains("build error"));
        assertTrue(recall.text().contains("tool_result"));
        assertTrue(recall.text().contains("main:2.0") && recall.text().contains("main:5.1"));
        assertTrue(chunks.stream().noneMatch(c -> c.text().contains("SECRET-RECALLED-TEXT")));
    }

    // ---- transcript

    @Test
    void transcriptRoundTripAndCrashDurability(@TempDir Path dir) throws IOException {
        var file = dir.resolve("transcript.jsonl");
        var writer = new TranscriptWriter(file);
        writer.writeTurn(plain(1, "a", "b"), T0);
        writer.writeTurn(toolTurn(2, "read", "{\"p\":1}", "out"), T0.plusSeconds(1));
        // crash: no close, and a torn partial line is left behind
        Files.writeString(file, "{\"type\":\"message\",\"agent\":\"main\",\"turn\":3,\"ts\":\"2026", StandardCharsets.UTF_8,
            StandardOpenOption.APPEND);
        var turns = TranscriptReader.read(file);
        assertEquals(2, turns.size());
        assertEquals(toolTurn(2, "read", "{\"p\":1}", "out"), turns.get(1).turn());
        assertEquals(T0.plusSeconds(1), turns.get(1).timestamp());
        for (var line : Files.readAllLines(file).subList(0, 6)) assertTrue(JSON.readTree(line).isObject());

        // reopen after the crash: new turns are readable, the torn line is ignored
        try (var again = new TranscriptWriter(file)) {
            again.writeTurn(plain(3, "c", "d"), T0.plusSeconds(2));
        }
        assertEquals(3, TranscriptReader.read(file).size());
        writer.close();
    }

    @Test
    void turnWithoutEndMarkerIsIgnored(@TempDir Path dir) throws IOException {
        var file = dir.resolve("t.jsonl");
        try (var w = new TranscriptWriter(file)) {
            w.writeTurn(plain(1, "a", "b"), T0);
        }
        var lines = Files.readAllLines(file);
        Files.write(file, lines.subList(0, lines.size() - 1)); // drop the turn_end line
        assertTrue(TranscriptReader.read(file).isEmpty());
    }

    // ---- manifest and store

    @Test
    void manifestAtomicReplaceLeavesNoTempFile(@TempDir Path dir) throws IOException {
        var file = dir.resolve("manifest.json");
        var m = SessionManifest.create("s1", T0, JSON.readTree("{\"a\":1}"));
        m.write(file);
        var next = m.withUpdate(T0.plusSeconds(5), 3, "running")
            .withAgents(List.of(new SessionManifest.AgentNode("main", null, "main")))
            .withPinned(List.of(new SessionManifest.PinnedItem("p1", "task", "do it")))
            .withPruning(List.of(new SessionManifest.PruneEntry("main:1.0", "AGGRESSIVE", 2,
                List.of(new SessionManifest.Span(0, 5)), null, List.of("kw"))));
        next.write(file);
        assertEquals(next, SessionManifest.read(file));
        var withRun = next.withImplementState(JSON.readTree("{\"status\":\"running\"}"));
        withRun.write(file);
        assertEquals(withRun, SessionManifest.read(file));
        assertEquals("running", SessionManifest.read(file).implementState().path("status").asString());
        assertEquals("running", withRun.withUpdate(T0, 5, "idle").implementState().path("status").asString());
        try (var files = Files.list(dir)) {
            assertEquals(List.of("manifest.json"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void storeCreatesListsAndResumesLatest(@TempDir Path project) throws IOException {
        var clock = new MutableClock(T0);
        var store = new SessionStore(project, clock);
        assertTrue(store.resumeLatest().isEmpty());
        var first = store.create(JeorjeConfig.defaults());
        clock.now = T0.plusSeconds(60);
        var second = store.create(JeorjeConfig.defaults());
        assertNotEquals(first.id(), second.id());
        assertEquals(List.of(second.id(), first.id()), store.list().stream().map(Session::id).toList());
        assertEquals(second.id(), store.resumeLatest().orElseThrow().id());

        // a turn updates the first session, so it becomes the latest
        clock.now = T0.plusSeconds(120);
        var updated = first.with(first.manifest().withUpdate(clock.instant(), 4, "idle"));
        updated.save();
        var resumed = store.resumeLatest().orElseThrow();
        assertEquals(first.id(), resumed.id());
        assertEquals(4, resumed.manifest().lastTurn());
        assertEquals("", resumed.manifest().config().path("swarm").path("token").asString());
        assertTrue(Files.isRegularFile(resumed.manifestPath()));
    }

    @Test
    void gitignoreIsCreatedAndExtended(@TempDir Path project) throws IOException {
        var store = new SessionStore(project);
        store.create(JeorjeConfig.defaults());
        var ignore = project.resolve(".jeorje/.gitignore");
        assertEquals(List.of("sessions/", "worktrees/"), Files.readAllLines(ignore));
        store.ensureGitignore();
        assertEquals(2, Files.readAllLines(ignore).size());
        Files.writeString(ignore, "custom\nsessions/\n");
        store.ensureGitignore();
        assertEquals(List.of("custom", "sessions/", "worktrees/"), Files.readAllLines(ignore));
    }

    // ---- rebuild

    @Test
    void rebuildGivesSameSearchResults(@TempDir Path dir) {
        var file = dir.resolve("transcript.jsonl");
        var chunker = new TurnChunker("s1");
        var live = new ListContextStore();
        var turns = List.of(
            plain(1, "fix the NullPointerException in ConfigLoader", "looking"),
            toolTurn(2, "read", "{\"path\":\"Config.java\"}", "class ConfigLoader { NullPointerException at line 412 }"),
            toolTurn(3, "recall", "{\"query\":\"ConfigLoader\"}", "found main:2.0 ConfigLoader NullPointerException"),
            toolTurn(4, "bash", "{}", ("log line\n\n".repeat(2000))));
        try (var w = new TranscriptWriter(file)) {
            for (var t : turns) {
                w.writeTurn(t, T0.plusSeconds(t.number()));
                live.append(chunker.chunk(t, T0.plusSeconds(t.number())));
            }
        }
        var rebuilt = new ListContextStore();
        var n = new IndexRebuilder(rebuilt, chunker).rebuild(file);
        assertEquals(live.chunks.size(), n);
        assertEquals(live.chunks, rebuilt.chunks);
        for (var term : List.of("ConfigLoader", "NullPointerException", "line", "recall")) {
            var q = new HistoryQuery(term, Syntax.TEXT, Filters.none(), 10);
            assertEquals(live.search(q), rebuilt.search(q), term);
        }
        // the recall result is a reference: the search by default skips it, and it holds no recalled text
        var recall = rebuilt.get("main:3.1").orElseThrow();
        assertEquals("recall", recall.toolName());
        assertFalse(recall.text().contains("found"));
        assertTrue(recall.text().contains("main:2.0"));
        assertTrue(rebuilt.search(new HistoryQuery("ConfigLoader", Syntax.TEXT, Filters.none(), 10)).stream()
            .noneMatch(h -> "recall".equals(h.chunk().toolName())));
    }

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
