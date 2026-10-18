package org.github.lscoughlin.jeorje.core.session;

import java.nio.file.Path;
import java.util.Objects;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rebuilds the index of a session from {@code transcript.jsonl}. It chunks each complete turn with
 * {@link TurnChunker}, so a {@code recall} result becomes a reference chunk and not a text chunk.
 */
public final class IndexRebuilder {
    private static final Logger log = LoggerFactory.getLogger(IndexRebuilder.class);

    private final ContextStore store;
    private final TurnChunker chunker;

    public IndexRebuilder(ContextStore store, TurnChunker chunker) {
        this.store = Objects.requireNonNull(store, "store");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
    }

    /** Appends the chunks of every complete turn to the store. Returns the number of chunks. */
    public int rebuild(Path transcript) {
        var count = 0;
        for (var timed : TranscriptReader.read(transcript)) {
            var chunks = chunker.chunk(timed.turn(), timed.timestamp());
            if (chunks.isEmpty()) continue;
            store.append(chunks);
            count += chunks.size();
        }
        var total = count;
        log.atInfo().log(() -> "rebuilt index: " + total + " chunks");
        return count;
    }
}
