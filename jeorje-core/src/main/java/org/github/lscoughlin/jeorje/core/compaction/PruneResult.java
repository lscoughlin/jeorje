package org.github.lscoughlin.jeorje.core.compaction;

import java.util.List;
import org.github.lscoughlin.jeorje.core.session.SessionManifest.PruneEntry;
import org.github.lscoughlin.jeorje.core.session.SessionManifest.Span;

/**
 * The pruning state of one chunk after a pruning step.
 *
 * @param note the decision note of a {@code CAREFUL} chunk at level 2, the topics line at level 3, or the
 *             stub text of a recall result; null otherwise
 * @param source who produced the result
 * @param keptChars the number of characters that stay (the length of the note when the note replaces the text)
 * @param originalChars the number of characters of the original chunk
 */
public record PruneResult(String chunkId, Protection protection, int level, List<Span> keptSpans, String note,
                          List<String> keywords, Source source, int keptChars, int originalChars) {
    /** Who produced a result. */
    public enum Source { NONE, MODEL, FALLBACK, STUB }

    public PruneResult {
        keptSpans = List.copyOf(keptSpans);
        keywords = List.copyOf(keywords);
    }

    /** The entry for the pruning table of the manifest. */
    public PruneEntry toEntry() {
        return new PruneEntry(chunkId, protection.name(), level, keptSpans, note, keywords);
    }
}
