package org.github.lscoughlin.jeorje.cli.interactive;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.github.lscoughlin.jeorje.swarm.SwarmHandlers;

/**
 * Caches the agents of the peers. A read never blocks: it returns the cached list and starts a background fetch
 * when the list is missing or older than the time to live. The tree of the roster uses it.
 */
final class RemoteRosters {
    private record Entry(List<SwarmHandlers.PeerAgent> agents, long fetchedAt) {}

    private final Function<String, CompletableFuture<List<SwarmHandlers.PeerAgent>>> fetch;
    private final LongSupplier clock;
    private final long ttlMs;
    private final Runnable onUpdate;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> loading = new ConcurrentHashMap<>();

    RemoteRosters(Function<String, CompletableFuture<List<SwarmHandlers.PeerAgent>>> fetch, LongSupplier clock,
                  long ttlMs, Runnable onUpdate) {
        this.fetch = fetch;
        this.clock = clock;
        this.ttlMs = ttlMs;
        this.onUpdate = onUpdate == null ? () -> { } : onUpdate;
    }

    /** The cached agents of a peer, or an empty list when none are known yet. */
    List<SwarmHandlers.PeerAgent> get(String peerId) {
        var entry = cache.get(peerId);
        var now = clock.getAsLong();
        if ((entry == null || now - entry.fetchedAt() > ttlMs) && loading.putIfAbsent(peerId, Boolean.TRUE) == null) {
            try {
                fetch.apply(peerId).whenComplete((agents, error) -> {
                    try {
                        // A failed fetch keeps the old list and tries again after the time to live.
                        var previous = cache.get(peerId);
                        cache.put(peerId, new Entry(error == null ? agents : (previous == null ? List.of()
                                : previous.agents()), clock.getAsLong()));
                        if (error == null) onUpdate.run();
                    } finally {
                        loading.remove(peerId);
                    }
                });
            } catch (RuntimeException e) {
                loading.remove(peerId);
            }
        }
        return entry == null ? List.of() : entry.agents();
    }
}
