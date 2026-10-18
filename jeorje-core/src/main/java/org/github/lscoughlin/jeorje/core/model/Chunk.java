package org.github.lscoughlin.jeorje.core.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One stored piece of history. The chunk id has the form {@code <agentId>:<turn>.<part>}.
 *
 * @param toolName the tool of the chunk, or null when no tool applies
 * @param sourceAgentId the agent that produced the content, or null when it is the agent in {@code agentId}.
 *                      A {@code subagent_summary} chunk has the parent in {@code agentId} and the child here.
 */
public record Chunk(String chunkId, String sessionId, String agentId, int turn, int part,
                    Instant timestamp, String kind, String toolName, String text, String sourceAgentId) {
    public Chunk {
        Objects.requireNonNull(chunkId, "chunkId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
    }

    /** A chunk with no source agent. */
    public Chunk(String chunkId, String sessionId, String agentId, int turn, int part,
                 Instant timestamp, String kind, String toolName, String text) {
        this(chunkId, sessionId, agentId, turn, part, timestamp, kind, toolName, text, null);
    }
}
