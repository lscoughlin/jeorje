package org.github.lscoughlin.jeorje.core.port;

import java.util.List;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.model.Chunk;

/** Port for the queryable history of a session. */
public interface ContextStore extends AutoCloseable {
    /** Stores the chunks of a finished turn. They are searchable when the call returns. */
    void append(List<Chunk> chunks);

    /** Searches all agents of the session. Results are ordered by score, best first, at most {@code limit}. */
    List<Hit> search(HistoryQuery query);

    /** Returns the chunk with this id, or empty. */
    Optional<Chunk> get(String chunkId);

    /** Returns the chunk and up to {@code before} chunks before it and {@code after} after it, in order. */
    List<Chunk> window(String chunkId, int before, int after);

    @Override
    void close();
}
