package org.github.lscoughlin.jeorje.testkit.swarm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A network of {@link InMemoryTransport} objects in one JVM. It can drop, delay, and reorder messages, and
 * it can cut links to simulate a partition. The send target is the address string of a transport.
 */
public final class InMemoryNetwork implements AutoCloseable {
    private final Map<String, InMemoryTransport> transports = new ConcurrentHashMap<>();
    private final Set<String> blocked = ConcurrentHashMap.newKeySet();
    private final Set<String> isolated = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2, r -> {
        var thread = new Thread(r, "in-memory-network");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private volatile double dropRate;
    private volatile long minDelayMs;
    private volatile long maxDelayMs;
    private volatile double reorderRate;
    private volatile long reorderExtraMs = 40;

    /** Makes a transport with an address, the peer id that it presents, and a token. */
    public InMemoryTransport join(String address, String peerId, String token) {
        var transport = new InMemoryTransport(this, address, peerId, token == null ? "" : token);
        if (transports.putIfAbsent(address, transport) != null) {
            throw new IllegalArgumentException("address is in use: " + address);
        }
        return transport;
    }

    public InMemoryTransport join(String address, String peerId) {
        return join(address, peerId, "");
    }

    /** Drops this share of all messages without an error for the sender. */
    public InMemoryNetwork dropRate(double rate) {
        this.dropRate = rate;
        return this;
    }

    /** Delays each message by a random time in the range. */
    public InMemoryNetwork delay(long minMs, long maxMs) {
        this.minDelayMs = minMs;
        this.maxDelayMs = Math.max(minMs, maxMs);
        return this;
    }

    /** Adds an extra random delay to this share of messages, so that later messages can arrive first. */
    public InMemoryNetwork reorder(double rate, long extraMs) {
        this.reorderRate = rate;
        this.reorderExtraMs = extraMs;
        return this;
    }

    /** Cuts all links between the two groups of addresses. Messages vanish without an error. */
    public void partition(Collection<String> groupA, Collection<String> groupB) {
        for (var a : groupA) {
            for (var b : groupB) {
                blocked.add(a + "->" + b);
                blocked.add(b + "->" + a);
            }
        }
    }

    /** Cuts all links of one address in both directions. */
    public void isolate(String address) {
        isolated.add(address);
    }

    /** Restores all links. */
    public void heal() {
        blocked.clear();
        isolated.clear();
    }

    public long delivered() {
        return delivered.get();
    }

    public long dropped() {
        return dropped.get();
    }

    /** The number of connections that failed the handshake. */
    public long rejected() {
        return rejected.get();
    }

    void remove(String address) {
        transports.remove(address);
    }

    void deliver(InMemoryTransport from, String target, String body) {
        var to = transports.get(target);
        if (to == null || to.isClosed()) {
            throw new UncheckedIOException(new ConnectException("no peer at " + target));
        }
        if (!to.token().equals(from.token())) {
            rejected.incrementAndGet();
            throw new UncheckedIOException(new IOException("handshake rejected: the proof of the token is wrong"));
        }
        if (body.length() > InMemoryTransport.MAX_MESSAGE_CHARS) {
            throw new IllegalArgumentException("message is above the limit");
        }
        var link = from.address() + "->" + target;
        if (blocked.contains(link) || isolated.contains(from.address()) || isolated.contains(target)
                || ThreadLocalRandom.current().nextDouble() < dropRate) {
            dropped.incrementAndGet();
            return;
        }
        var random = ThreadLocalRandom.current();
        var delay = maxDelayMs > 0 ? minDelayMs + (long) (random.nextDouble() * (maxDelayMs - minDelayMs)) : 0L;
        if (reorderRate > 0 && random.nextDouble() < reorderRate) {
            delay += 1 + (long) (random.nextDouble() * reorderExtraMs);
        }
        if (delay <= 0) {
            to.enqueue(from.peerId(), body);
            delivered.incrementAndGet();
        } else {
            timer.schedule(() -> {
                to.enqueue(from.peerId(), body);
                delivered.incrementAndGet();
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void close() {
        transports.values().forEach(InMemoryTransport::close);
        timer.shutdownNow();
    }
}
