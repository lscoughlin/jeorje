package org.github.lscoughlin.jeorje.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.github.lscoughlin.jeorje.testkit.swarm.InMemoryNetwork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryTest {

    @Test
    void peerIdIsRandomAndSaved(@TempDir Path dir) {
        var first = PeerIdStore.loadOrCreate(dir.resolve("state"));
        assertEquals(first, PeerIdStore.loadOrCreate(dir.resolve("state")));
        assertFalse(first.isBlank());
        assertFalse(first.equals(PeerIdStore.loadOrCreate(dir.resolve("other"))));
    }

    @Test
    void aServiceBecomesAnEntryWithTheAdvertisedAddress() {
        var entry = ZeroconfDiscovery.entryOf("a", 7601, "a", "192.168.1.5:7601", "alpha", List.of("10.0.0.2"));
        assertEquals(new PeerDiscovery.Entry("a", "192.168.1.5:7601", "alpha"), entry);
    }

    @Test
    void aWildcardAddressIsReplacedByTheResolvedHost() {
        var entry = ZeroconfDiscovery.entryOf("a", 7601, "a", "0.0.0.0:7601", "alpha", List.of("10.0.0.2"));
        assertEquals("10.0.0.2:7601", entry.address());
        assertNull(ZeroconfDiscovery.entryOf("a", 7601, "a", "0.0.0.0:7601", "alpha", List.of()));
    }

    @Test
    void theServiceNameIsThePeerIdWhenTheRecordHasNone() {
        var entry = ZeroconfDiscovery.entryOf("svc", 7601, null, "10.0.0.3:7601", null, List.of());
        assertEquals("svc", entry.peerId());
        assertEquals("", entry.name());
    }

    @Test
    void theMemoryDiscoveryListsOthersAndForgetsWithdrawnPeers() {
        var shared = new java.util.concurrent.ConcurrentHashMap<String, PeerDiscovery.Entry>();
        var one = new MemoryDiscovery(shared);
        one.announce(PeerRecord.of("a", "127.0.0.1:7601", "alpha"));
        one.announce(PeerRecord.of("b", "127.0.0.1:7602", "beta"));
        assertEquals(List.of("127.0.0.1:7602"), one.addresses("a"));
        one.withdraw("b");
        assertTrue(one.addresses("a").isEmpty());
    }

    @Test
    void instancesFindEachOtherThroughDiscovery() {
        var shared = new java.util.concurrent.ConcurrentHashMap<String, PeerDiscovery.Entry>();
        try (var network = new InMemoryNetwork()) {
            var nodes = new java.util.ArrayList<SwarmNode>();
            for (var name : List.of("x", "y", "z")) {
                var config = SwarmConfig.defaults().withGossipIntervalMs(30).withPingTimeoutMs(40);
                var transport = network.join("mem:" + name, name);
                var node = new SwarmNode(config, PeerRecord.of(name, "mem:" + name, name), transport,
                        Clock.systemUTC(), new SwarmHandlers() {}, new MemoryDiscovery(shared));
                nodes.add(node.start());
            }
            assertTrue(Cluster.await(() -> Cluster.allAlive(nodes), 10_000));
            nodes.get(0).close();
            assertFalse(shared.containsKey("x"), "a closed instance withdraws its announcement");
            nodes.forEach(SwarmNode::kill);
        }
    }
}
