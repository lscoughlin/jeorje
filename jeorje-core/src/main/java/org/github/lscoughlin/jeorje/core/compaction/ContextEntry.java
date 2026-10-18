package org.github.lscoughlin.jeorje.core.compaction;

import java.util.Objects;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.session.SessionManifest.PruneEntry;

/**
 * One chunk in the working context.
 *
 * @param prune the pruning entry, or null at level 0
 */
public record ContextEntry(Chunk chunk, Protection protection, PruneEntry prune) {
    public ContextEntry {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(protection, "protection");
    }

    public static ContextEntry of(Chunk chunk, Protection protection) { return new ContextEntry(chunk, protection, null); }

    public int level() { return prune == null ? 0 : prune.level(); }

    /** The text that the model sees. */
    public String render() { return PrunedChunks.render(chunk, prune); }

    public int tokens() { return Tokens.estimate(render()); }

    ContextEntry with(PruneResult r) {
        return new ContextEntry(chunk, r.protection(), r.level() == 0 ? null : r.toEntry());
    }
}
