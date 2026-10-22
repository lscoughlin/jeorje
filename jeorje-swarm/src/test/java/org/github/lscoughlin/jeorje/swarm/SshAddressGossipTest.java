package org.github.lscoughlin.jeorje.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.testkit.swarm.InMemoryNetwork;
import org.junit.jupiter.api.Test;

class SshAddressGossipTest {

    @Test
    void everyNodeLearnsTheSshAddressOfEveryOtherNode() {
        try (var network = new InMemoryNetwork()) {
            var nodes = new ArrayList<SwarmNode>();
            for (var name : List.of("x", "y", "z")) {
                var config = SwarmConfig.defaults().withGossipIntervalMs(30).withPingTimeoutMs(40)
                        .withWka(name.equals("x") ? List.of() : List.of("mem:x"));
                var node = new SwarmNode(config, PeerRecord.of(name, "mem:" + name, name),
                        network.join("mem:" + name, name), Clock.systemUTC(), new SwarmHandlers() {}, null,
                        "ssh-" + name + ":22");
                nodes.add(node.start());
            }
            try {
                assertTrue(Cluster.await(() -> Cluster.allAlive(nodes), 10_000));
                for (var node : nodes) {
                    for (var other : nodes) {
                        assertTrue(Cluster.await(() -> ("ssh-" + other.peerId() + ":22")
                                .equals(node.members().get(other.peerId()).sshAddress()), 5_000),
                                node.peerId() + " does not know the SSH address of " + other.peerId());
                    }
                }
            } finally {
                nodes.forEach(SwarmNode::kill);
            }
        }
    }

    @Test
    void aNullSshAddressBecomesBlankAndMembershipKeepsTheWinningRecord() {
        var node = new SwarmNode(SwarmConfig.defaults(), PeerRecord.of("a", "mem:a", "a"), null, Clock.systemUTC(),
                new SwarmHandlers() {}, null, null);
        assertEquals("", node.self().sshAddress());
        var membership = new Membership(PeerRecord.of("m", "mem:m", "m"), SwarmConfig.defaults(), Clock.systemUTC());
        var older = PeerRecord.of("p", "mem:p", "p").withSshAddress("old:1");
        var newer = older.withIncarnation(1).withSshAddress("new:2");
        membership.merge(older);
        membership.merge(newer);
        membership.merge(older);
        assertEquals("new:2", membership.get("p").sshAddress());
    }
}
