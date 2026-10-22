package org.github.lscoughlin.jeorje.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalRendezvousTest {

    private static final class MovingClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return now.get(); }

        void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }
    }

    private static PeerRecord record(String id, String address, String ssh) {
        return PeerRecord.of(id, address, id).withSshAddress(ssh);
    }

    @Test
    void twoInstancesSeeEachOtherButNotThemselves(@TempDir Path dir) {
        var clock = new MovingClock();
        var a = new LocalRendezvous(dir, clock);
        var b = new LocalRendezvous(dir, clock);
        try {
            a.announce(record("a", "127.0.0.1:1001", "127.0.0.1:2001"));
            b.announce(record("b", "127.0.0.1:1002", "127.0.0.1:2002"));
            assertEquals(List.of(new PeerDiscovery.Entry("b", "127.0.0.1:1002", "127.0.0.1:2002", "b")),
                    a.peers("a"));
            assertEquals(List.of("127.0.0.1:1001"), b.addresses("b"));
            assertEquals("127.0.0.1:2001", b.peers("b").getFirst().sshAddress());
        } finally {
            a.withdraw("a");
            b.withdraw("b");
        }
    }

    @Test
    void aStaleRecordIsIgnoredUntilTheOwnerRefreshesIt(@TempDir Path dir) {
        var clock = new MovingClock();
        var a = new LocalRendezvous(dir, clock);
        var b = new LocalRendezvous(dir, clock);
        try {
            a.announce(record("a", "127.0.0.1:1001", ""));
            clock.advance(Duration.ofSeconds(31));
            assertTrue(b.peers("b").isEmpty());
            a.refresh();
            assertEquals(1, b.peers("b").size());
        } finally {
            a.withdraw("a");
        }
    }

    @Test
    void aRecordOfADeadProcessAndABrokenFileAreIgnored(@TempDir Path dir) throws Exception {
        var clock = new MovingClock();
        var directory = Files.createDirectories(dir.resolve("swarm").resolve("rendezvous"));
        Files.writeString(directory.resolve("ghost.json"), "{\"peerId\":\"ghost\",\"address\":\"127.0.0.1:9\","
                + "\"sshAddress\":\"\",\"name\":\"ghost\",\"pid\":999999999,\"updatedAt\":" + clock.millis() + "}");
        Files.writeString(directory.resolve("broken.json"), "not json");
        assertTrue(new LocalRendezvous(dir, clock).peers("me").isEmpty());
    }

    @Test
    void withdrawDeletesTheFile(@TempDir Path dir) {
        var clock = new MovingClock();
        var a = new LocalRendezvous(dir, clock);
        var b = new LocalRendezvous(dir, clock);
        a.announce(record("a", "127.0.0.1:1001", ""));
        assertEquals(1, b.peers("b").size());
        a.withdraw("a");
        assertFalse(Files.exists(dir.resolve("swarm/rendezvous/a.json")));
        assertTrue(b.peers("b").isEmpty());
    }

    @Test
    void theCompositeMergesAndDeduplicatesByPeerId() {
        var first = new ConcurrentHashMap<String, PeerDiscovery.Entry>();
        var second = new ConcurrentHashMap<String, PeerDiscovery.Entry>();
        first.put("x", new PeerDiscovery.Entry("x", "h:1", "", "x"));
        second.put("x", new PeerDiscovery.Entry("x", "h:1", "h:2", "x"));
        second.put("y", new PeerDiscovery.Entry("y", "h:3", "", "y"));
        var composite = new CompositeDiscovery(List.of(new MemoryDiscovery(first), new MemoryDiscovery(second)));
        var peers = composite.peers("me");
        assertEquals(2, peers.size());
        assertEquals("h:2", peers.stream().filter(e -> e.peerId().equals("x")).findFirst().orElseThrow().sshAddress());
        composite.announce(PeerRecord.of("me", "h:9", "me"));
        assertTrue(first.containsKey("me") && second.containsKey("me"));
        composite.withdraw("me");
        assertFalse(first.containsKey("me") || second.containsKey("me"));
    }
}
