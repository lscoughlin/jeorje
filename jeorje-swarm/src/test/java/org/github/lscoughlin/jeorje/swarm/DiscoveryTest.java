package org.github.lscoughlin.jeorje.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
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
    void discoveryFilesListOtherInstancesAndSkipBrokenFiles(@TempDir Path dir) throws Exception {
        var one = new LocalDiscovery(LocalDiscovery.defaultDirectory(dir));
        one.announce(PeerRecord.of("a", "127.0.0.1:7601", "alpha"));
        one.announce(PeerRecord.of("b", "127.0.0.1:7602", "beta"));
        Files.writeString(dir.resolve("peers").resolve("broken.json"), "not json");
        assertEquals(List.of("127.0.0.1:7602"), one.addresses("a"));
        one.withdraw("b");
        assertTrue(one.addresses("a").isEmpty());
    }

    @Test
    void instancesOnOneHostFindEachOtherThroughTheFiles(@TempDir Path dir) {
        var directory = dir.resolve("peers");
        try (var network = new InMemoryNetwork()) {
            var nodes = new java.util.ArrayList<SwarmNode>();
            for (var name : List.of("x", "y", "z")) {
                var config = SwarmConfig.defaults().withGossipIntervalMs(30).withPingTimeoutMs(40);
                var transport = network.join("mem:" + name, name);
                var node = new SwarmNode(config, PeerRecord.of(name, "mem:" + name, name), transport,
                        Clock.systemUTC(), new SwarmHandlers() {}, new LocalDiscovery(directory));
                nodes.add(node.start());
            }
            assertTrue(Cluster.await(() -> Cluster.allAlive(nodes), 10_000));
            nodes.get(0).close();
            assertFalse(Files.exists(directory.resolve("x.json")), "a closed instance removes its file");
            nodes.forEach(SwarmNode::kill);
        }
    }
}
