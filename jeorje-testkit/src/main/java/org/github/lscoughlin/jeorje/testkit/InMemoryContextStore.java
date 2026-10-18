package org.github.lscoughlin.jeorje.testkit;

import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.Filters;
import org.github.lscoughlin.jeorje.core.port.Hit;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** A list-based {@link ContextStore}. Search counts the query words that appear in a chunk. */
public final class InMemoryContextStore implements ContextStore {
    private final List<Chunk> chunks = new ArrayList<>();
    private boolean closed;

    @Override
    public synchronized void append(List<Chunk> added) { chunks.addAll(added); }

    /** All stored chunks, in order. */
    public synchronized List<Chunk> all() { return List.copyOf(chunks); }

    public synchronized boolean isClosed() { return closed; }

    @Override
    public synchronized List<Hit> search(HistoryQuery query) {
        var words = query.text() == null ? new String[0]
            : query.text().toLowerCase(Locale.ROOT).split("\\W+");
        var filters = query.filters() == null ? Filters.none() : query.filters();
        var hits = new ArrayList<Hit>();
        for (var chunk : chunks) {
            if (!matches(chunk, filters)) continue;
            var lower = chunk.text().toLowerCase(Locale.ROOT);
            var score = 0;
            for (var word : words) if (!word.isEmpty() && lower.contains(word)) score++;
            if (score > 0) hits.add(new Hit(chunk, score, snippet(chunk.text())));
        }
        hits.sort(Comparator.comparing(Hit::score).reversed());
        return hits.size() > query.limit() ? List.copyOf(hits.subList(0, query.limit())) : List.copyOf(hits);
    }

    private static String snippet(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200);
    }

    private static boolean matches(Chunk c, Filters f) {
        if (!f.agentIds().isEmpty() && !f.agentIds().contains(c.agentId())) return false;
        if (f.fromTurn().isPresent() && c.turn() < f.fromTurn().getAsInt()) return false;
        if (f.toTurn().isPresent() && c.turn() > f.toTurn().getAsInt()) return false;
        if (f.from().isPresent() && c.timestamp().isBefore(f.from().get())) return false;
        if (f.to().isPresent() && c.timestamp().isAfter(f.to().get())) return false;
        if (!f.kinds().isEmpty() && !f.kinds().contains(c.kind())) return false;
        return f.toolNames().isEmpty() || (c.toolName() != null && f.toolNames().contains(c.toolName()));
    }

    @Override
    public synchronized Optional<Chunk> get(String chunkId) {
        return chunks.stream().filter(c -> c.chunkId().equals(chunkId)).findFirst();
    }

    @Override
    public synchronized List<Chunk> window(String chunkId, int before, int after) {
        for (var i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).chunkId().equals(chunkId)) {
                return List.copyOf(chunks.subList(Math.max(0, i - before), Math.min(chunks.size(), i + after + 1)));
            }
        }
        return List.of();
    }

    @Override
    public synchronized void close() { closed = true; }
}
