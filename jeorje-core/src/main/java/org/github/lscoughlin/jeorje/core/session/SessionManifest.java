package org.github.lscoughlin.jeorje.core.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The manifest of a session: agent tree, config snapshot, pinned items, pruning table, and run state.
 * {@code runState} is the life-cycle word of the session. {@code implementState} is the JSON state of the
 * {@code /implement} run, or null when no run exists.
 * {@link #write(Path)} replaces the file atomically.
 */
public record SessionManifest(String sessionId, Instant createdAt, Instant updatedAt, List<AgentNode> agents,
                              JsonNode config, List<PinnedItem> pinned, List<PruneEntry> pruning,
                              String runState, int lastTurn, JsonNode implementState) {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** One agent in the tree. {@code parentId} is null for the root agent. */
    public record AgentNode(String id, String parentId, String name) {}

    /** An item that compaction never prunes. */
    public record PinnedItem(String id, String kind, String text) {}

    /** A span of kept characters in the original chunk text. */
    public record Span(int start, int end) {}

    /** The pruning state of one chunk. */
    public record PruneEntry(String chunkId, String protection, int level, List<Span> keptSpans,
                             String note, List<String> keywords) {
        public PruneEntry {
            keptSpans = keptSpans == null ? List.of() : List.copyOf(keptSpans);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }

    /** A manifest with no {@code /implement} state. */
    public SessionManifest(String sessionId, Instant createdAt, Instant updatedAt, List<AgentNode> agents,
                           JsonNode config, List<PinnedItem> pinned, List<PruneEntry> pruning,
                           String runState, int lastTurn) {
        this(sessionId, createdAt, updatedAt, agents, config, pinned, pruning, runState, lastTurn, null);
    }

    public SessionManifest {
        if (implementState != null && (implementState.isNull() || implementState.isMissingNode())) {
            implementState = null;
        }
        agents = agents == null ? List.of() : List.copyOf(agents);
        pinned = pinned == null ? List.of() : List.copyOf(pinned);
        pruning = pruning == null ? List.of() : List.copyOf(pruning);
    }

    /** A new manifest with no agents, pins, or pruning. */
    public static SessionManifest create(String sessionId, Instant now, JsonNode config) {
        return new SessionManifest(sessionId, now, now, List.of(), config, List.of(), List.of(), "idle", 0);
    }

    public SessionManifest withUpdate(Instant now, int turn, String state) {
        return new SessionManifest(sessionId, createdAt, now, agents, config, pinned, pruning, state, turn, implementState);
    }

    public SessionManifest withAgents(List<AgentNode> value) {
        return new SessionManifest(sessionId, createdAt, updatedAt, value, config, pinned, pruning, runState, lastTurn, implementState);
    }

    public SessionManifest withPinned(List<PinnedItem> value) {
        return new SessionManifest(sessionId, createdAt, updatedAt, agents, config, value, pruning, runState, lastTurn, implementState);
    }

    public SessionManifest withPruning(List<PruneEntry> value) {
        return new SessionManifest(sessionId, createdAt, updatedAt, agents, config, pinned, value, runState, lastTurn, implementState);
    }

    public SessionManifest withImplementState(JsonNode value) {
        return new SessionManifest(sessionId, createdAt, updatedAt, agents, config, pinned, pruning, runState,
            lastTurn, value);
    }

    /** Writes a temporary file, forces it to disk, and moves it over the target atomically. */
    public void write(Path file) {
        try {
            var dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            var tmp = dir.resolve(file.getFileName() + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this),
                StandardCharsets.UTF_8);
            try (var ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static SessionManifest read(Path file) {
        try {
            return MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), SessionManifest.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
