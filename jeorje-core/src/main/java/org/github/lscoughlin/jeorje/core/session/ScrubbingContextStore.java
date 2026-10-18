package org.github.lscoughlin.jeorje.core.session;

import java.util.List;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.config.SecretScrubber;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;
import org.github.lscoughlin.jeorje.core.port.Hit;

/**
 * A {@link ContextStore} that removes secrets from the text of each chunk before the chunk enters the index. All
 * other calls go to the wrapped store.
 */
public final class ScrubbingContextStore implements ContextStore {
    private final ContextStore delegate;
    private final SecretScrubber scrubber;
    private final java.util.concurrent.atomic.AtomicInteger appended = new java.util.concurrent.atomic.AtomicInteger();

    public ScrubbingContextStore(ContextStore delegate, SecretScrubber scrubber) {
        this.delegate = delegate;
        this.scrubber = scrubber;
    }

    /** The store that this store wraps. */
    public ContextStore delegate() {
        return delegate;
    }

    /** The number of chunks that this object appended. It is a lower bound for an index that existed before. */
    public int appendedCount() {
        return appended.get();
    }

    @Override
    public void append(List<Chunk> chunks) {
        appended.addAndGet(chunks.size());
        delegate.append(chunks.stream().map(this::clean).toList());
    }

    private Chunk clean(Chunk c) {
        var text = scrubber.scrub(c.text());
        if (text.equals(c.text())) return c;
        return new Chunk(c.chunkId(), c.sessionId(), c.agentId(), c.turn(), c.part(), c.timestamp(), c.kind(),
                c.toolName(), text, c.sourceAgentId());
    }

    @Override
    public List<Hit> search(HistoryQuery query) {
        return delegate.search(query);
    }

    @Override
    public Optional<Chunk> get(String chunkId) {
        return delegate.get(chunkId);
    }

    @Override
    public List<Chunk> window(String chunkId, int before, int after) {
        return delegate.window(chunkId, before, after);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
