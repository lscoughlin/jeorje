package org.github.lscoughlin.jeorje.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.config.SecretScrubber;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.model.Turn;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;
import org.github.lscoughlin.jeorje.core.port.Hit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** The secret rules for the transcript and the index. */
class SecretHandlingTest {
    private static final String KEY = "sk-or-v1-0123456789abcdef0123456789abcdef";
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SecretScrubber scrubber = new SecretScrubber().register(KEY);

    @Test
    void scrubTreeCleansEveryTextValueAndKeepsTheJsonValid() {
        var tree = JSON.readTree("{\"a\":\"x " + KEY + " y\",\"b\":[\"" + KEY + "\",{\"c\":\"token: \\\"abc12345\\\"\"}],\"n\":5}");
        scrubber.scrubTree(tree);
        var text = JSON.writeValueAsString(tree);
        assertFalse(text.contains(KEY), text);
        assertEquals("x *** y", tree.get("a").asString());
        assertEquals("***", tree.get("b").get(0).asString());
        assertEquals(5, tree.get("n").asInt());
        assertTrue(JSON.readTree(text).isObject());
    }

    @Test
    void transcriptHasNoKeyEvenWhenAToolPrintsIt(@TempDir Path dir) throws IOException {
        var file = dir.resolve("transcript.jsonl");
        var agent = new AgentId("main");
        var turn = new Turn(agent, 1, List.of(Message.user("show the key"),
                new Message(Role.ASSISTANT, List.of(new ToolUse("u1", "read", JSON.readTree("{\"path\":\"k\"}")))),
                new Message(Role.USER, List.of(new ToolResult("u1", "the key is " + KEY, false))),
                Message.assistant("api_key=" + KEY + " and a second use of " + KEY)));
        try (var writer = new TranscriptWriter(file, scrubber)) {
            writer.writeTurn(turn, T0);
        }
        var raw = Files.readString(file);
        assertFalse(raw.contains(KEY), raw);
        assertTrue(raw.contains("***"));
        var read = TranscriptReader.read(file);
        assertEquals(1, read.size());
        assertTrue(read.get(0).turn().messages().get(2).content().toString().contains("the key is ***"));
    }

    @Test
    void transcriptWithoutScrubberKeepsTheText(@TempDir Path dir) throws IOException {
        var file = dir.resolve("t.jsonl");
        try (var writer = new TranscriptWriter(file)) {
            writer.writeTurn(new Turn(new AgentId("main"), 1, List.of(Message.user(KEY), Message.assistant("ok"))), T0);
        }
        assertTrue(Files.readString(file).contains(KEY));
    }

    @Test
    void chunksAreScrubbedBeforeTheyEnterTheIndex() {
        var inner = new ListStore();
        try (var store = new ScrubbingContextStore(inner, scrubber)) {
            var dirty = new Chunk("main:1.0", "s1", "main", 1, 0, T0, "turn", "read", "found " + KEY + " here");
            var clean = new Chunk("main:2.0", "s1", "main", 2, 0, T0, "turn", null, "nothing here");
            store.append(List.of(dirty, clean));
            assertEquals(2, inner.chunks.size());
            assertEquals("found *** here", inner.chunks.get(0).text());
            assertEquals("main:1.0", inner.chunks.get(0).chunkId());
            assertEquals("read", inner.chunks.get(0).toolName());
            assertEquals(clean, inner.chunks.get(1));
            assertTrue(store.get("main:1.0").isPresent());
            assertFalse(store.get("main:1.0").orElseThrow().text().contains(KEY));
        }
        assertTrue(inner.closed);
    }

    @Test
    void thePatternsCatchKeysThatTheScrubberDoesNotKnow() {
        var other = new SecretScrubber();
        assertFalse(other.scrub("ghp_abcdefghijklmnopqrstuvwxyz0123").contains("ghp_"));
        assertFalse(other.scrub("Authorization: Bearer abcdefghijklmnop12345678").contains("abcdefghijklmnop12345678"));
        assertFalse(other.scrub("password=hunter2hunter2").contains("hunter2"));
    }

    private static final class ListStore implements ContextStore {
        final List<Chunk> chunks = new ArrayList<>();
        boolean closed;

        @Override public void append(List<Chunk> c) { chunks.addAll(c); }
        @Override public List<Hit> search(HistoryQuery query) { return List.of(); }
        @Override public Optional<Chunk> get(String id) {
            return chunks.stream().filter(c -> c.chunkId().equals(id)).findFirst();
        }
        @Override public List<Chunk> window(String id, int before, int after) { return List.of(); }
        @Override public void close() { closed = true; }
    }
}
