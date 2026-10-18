package org.github.lscoughlin.jeorje.core.compaction;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.Filters;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;
import org.github.lscoughlin.jeorje.core.port.Syntax;

/** The inverse document frequency of terms in the session index. */
public interface TermStats {
    /** Every term has the same weight. */
    TermStats UNIFORM = term -> 1.0;

    /** The largest number of hits that one frequency query counts. */
    int MAX_DOCUMENT_FREQUENCY = 200;

    double idf(String term);

    /** Computes the frequency with TEXT queries on the store. The document count is fixed. */
    static TermStats of(ContextStore store, int docCount) {
        return of(store, () -> docCount);
    }

    /**
     * Computes the weights from the session index with TEXT queries on the store. Chunks of {@code recall}
     * calls do not count. The weight is {@code ln((N + 1) / (df + 1)) + 1}. The document count N is at least the
     * document frequency df. The object keeps the weight of each term until the document count grows by more
     * than 10%, because the history only grows by append.
     *
     * @param docCount the number of chunks in the index
     */
    static TermStats of(ContextStore store, IntSupplier docCount) {
        var cache = new ConcurrentHashMap<String, Double>();
        var cachedCount = new int[] {-1};
        return term -> {
            var n = docCount.getAsInt();
            synchronized (cachedCount) {
                if (cachedCount[0] < 0 || n > cachedCount[0] + Math.max(1, cachedCount[0] / 10)) {
                    cache.clear();
                    cachedCount[0] = n;
                }
            }
            if (term.chars().noneMatch(Character::isLetterOrDigit)) return 1.0;
            return cache.computeIfAbsent(term, t -> {
                var df = 0;
                try {
                    df = (int) store.search(new HistoryQuery(KeywordHints.quote(t), Syntax.TEXT, Filters.none(),
                        MAX_DOCUMENT_FREQUENCY)).stream().filter(h -> !"recall".equals(h.chunk().toolName())).count();
                } catch (RuntimeException e) {
                    df = 0;
                }
                return Math.log((Math.max(n, df) + 1.0) / (df + 1.0)) + 1.0;
            });
        };
    }
}
