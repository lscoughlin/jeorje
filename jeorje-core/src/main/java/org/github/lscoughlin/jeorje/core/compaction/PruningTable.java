package org.github.lscoughlin.jeorje.core.compaction;

import java.util.HashMap;
import java.util.List;
import java.util.function.Function;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.session.SessionManifest;
import org.github.lscoughlin.jeorje.core.session.SessionManifest.PruneEntry;

/** Converts between the working context and the pruning table of the manifest. */
public final class PruningTable {
    private PruningTable() {}

    /** The entries for the manifest: one for each pruned chunk. */
    public static List<PruneEntry> toEntries(List<ContextEntry> entries) {
        return entries.stream().filter(e -> e.level() > 0).map(ContextEntry::prune).toList();
    }

    /** Writes the table into the manifest. */
    public static SessionManifest store(SessionManifest manifest, List<ContextEntry> entries) {
        return manifest.withPruning(toEntries(entries));
    }

    /**
     * Rebuilds the entries on resume. A chunk with a table row gets the stored class, level, spans, and note. A
     * chunk without a row is at level 0 with the class from {@code classOf}.
     */
    public static List<ContextEntry> restore(List<Chunk> chunks, List<PruneEntry> table,
                                             Function<Chunk, Protection> classOf) {
        var byId = new HashMap<String, PruneEntry>();
        table.forEach(e -> byId.put(e.chunkId(), e));
        return chunks.stream().map(c -> {
            var row = byId.get(c.chunkId());
            return row == null ? ContextEntry.of(c, classOf.apply(c))
                : new ContextEntry(c, Protection.valueOf(row.protection()), row);
        }).toList();
    }
}
