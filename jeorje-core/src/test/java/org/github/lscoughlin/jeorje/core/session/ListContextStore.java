package org.github.lscoughlin.jeorje.core.session;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.Hit;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;

/** A tiny term-count store for tests. Chunks with tool name recall are skipped unless filtered in. */
final class ListContextStore implements ContextStore {
    final List<Chunk> chunks = new ArrayList<>();

    @Override public void append(List<Chunk> c) { chunks.addAll(c); }

    @Override public List<Hit> search(HistoryQuery q) {
        var terms = Arrays.asList(q.text().toLowerCase().split("\\W+"));
        return chunks.stream()
            .filter(c -> !"recall".equals(c.toolName()) || q.filters().toolNames().contains("recall"))
            .map(c -> new Hit(c, terms.stream().mapToInt(t -> count(c.text().toLowerCase(), t)).sum(), ""))
            .filter(h -> h.score() > 0)
            .sorted(Comparator.comparingDouble((Hit h) -> -h.score()).thenComparing(h -> h.chunk().chunkId()))
            .limit(q.limit()).toList();
    }

    private static int count(String text, String term) {
        var n = 0;
        for (var i = text.indexOf(term); i >= 0 && !term.isEmpty(); i = text.indexOf(term, i + 1)) n++;
        return n;
    }

    @Override public Optional<Chunk> get(String id) {
        return chunks.stream().filter(c -> c.chunkId().equals(id)).findFirst();
    }

    @Override public List<Chunk> window(String id, int before, int after) {
        for (var i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).chunkId().equals(id)) {
                return List.copyOf(chunks.subList(Math.max(0, i - before), Math.min(chunks.size(), i + after + 1)));
            }
        }
        return List.of();
    }

    @Override public void close() {}
}
