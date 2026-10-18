package org.github.lscoughlin.jeorje.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GossipSwarmTest {

    @ParameterizedTest
    @ValueSource(ints = {3, 8, 20})
    void everyPeerJoinsWithAWkaList(int size) {
        try (var cluster = new Cluster()) {
            cluster.add("n0", List.of());
            for (var i = 1; i < size; i++) {
                cluster.add("n" + i, List.of("n0"));
            }
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 15_000), "all peers see all peers");
        }
    }

    @Test
    void gossipOnlyJoinLearnsTheFullMembership() {
        try (var cluster = new Cluster()) {
            var a = cluster.add("a", List.of());
            cluster.add("b", List.of());
            cluster.add("c", List.of());
            cluster.add("d", List.of());
            // Only b and c contact a, d contacts c. Nobody has the full list.
            cluster.node("b").join(a.address());
            cluster.node("c").join(Cluster.address("b"));
            cluster.node("d").join(Cluster.address("c"));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 10_000));
        }
    }

    @Test
    void aPeerWithNoWkaListWaitsUntilOthersContactIt() throws Exception {
        try (var cluster = new Cluster()) {
            var lonely = cluster.add("lonely", List.of());
            Thread.sleep(150);
            assertEquals(1, lonely.members().size());
            cluster.add("visitor", List.of("lonely"));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));
        }
    }

    @Test
    void aCrashedPeerBecomesSuspectThenDead() {
        try (var cluster = new Cluster()) {
            var states = new CopyOnWriteArrayList<PeerState>();
            var watcher = new SwarmHandlers() {
                @Override
                public void onPeerChange(PeerRecord record) {
                    if (record.peerId().equals("victim")) {
                        states.add(record.state());
                    }
                }
            };
            cluster.add("n0", List.of(), "", watcher);
            cluster.add("n1", List.of("n0"));
            cluster.add("n2", List.of("n0"));
            cluster.add("victim", List.of("n0"));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 10_000));
            cluster.node("victim").kill();
            assertTrue(Cluster.await(() -> Cluster.sees(cluster.node("n0"), PeerState.DEAD, "victim")
                    && Cluster.sees(cluster.node("n1"), PeerState.DEAD, "victim")
                    && Cluster.sees(cluster.node("n2"), PeerState.DEAD, "victim"), 10_000));
            assertTrue(states.contains(PeerState.SUSPECT), "the watcher saw SUSPECT first: " + states);
            assertTrue(states.indexOf(PeerState.SUSPECT) < states.indexOf(PeerState.DEAD));
        }
    }

    @Test
    void aSuspectedPeerRefutesWithAHigherIncarnation() {
        try (var cluster = new Cluster()) {
            cluster.base = cluster.base.withSuspectTimeoutMs(60_000);
            cluster.add("n0", List.of());
            cluster.add("n1", List.of("n0"));
            cluster.add("n2", List.of("n0"));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 10_000));
            cluster.network.isolate(Cluster.address("n2"));
            assertTrue(Cluster.await(() -> Cluster.sees(cluster.node("n0"), PeerState.SUSPECT, "n2")
                    || Cluster.sees(cluster.node("n1"), PeerState.SUSPECT, "n2"), 10_000));
            cluster.network.heal();
            assertTrue(Cluster.await(() -> cluster.node("n2").self().incarnation() >= 1
                    && Cluster.allAlive(cluster.nodes), 10_000));
            assertTrue(cluster.node("n0").members().get("n2").incarnation() >= 1);
        }
    }

    @Test
    void aPartitionSplitsAndHeals() {
        try (var cluster = new Cluster()) {
            cluster.base = cluster.base.withSuspectTimeoutMs(200);
            var names = List.of("p0", "p1", "p2", "p3", "p4", "p5");
            cluster.add("p0", List.of());
            names.stream().skip(1).forEach(n -> cluster.add(n, List.of("p0")));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 10_000));
            var left = List.of("p0", "p1", "p2").stream().map(Cluster::address).toList();
            var right = List.of("p3", "p4", "p5").stream().map(Cluster::address).toList();
            cluster.network.partition(left, right);
            assertTrue(Cluster.await(() -> Cluster.sees(cluster.node("p0"), PeerState.DEAD, "p3", "p4", "p5")
                    && Cluster.sees(cluster.node("p4"), PeerState.DEAD, "p0", "p1", "p2")
                    && Cluster.sees(cluster.node("p1"), PeerState.ALIVE, "p0", "p2"), 15_000));
            cluster.network.heal();
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 20_000), "the swarm heals");
        }
    }

    @Test
    void aPeerThatLeavesIsMarkedLeft() {
        try (var cluster = new Cluster()) {
            cluster.add("n0", List.of());
            cluster.add("n1", List.of("n0"));
            cluster.add("n2", List.of("n0"));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 10_000));
            cluster.node("n2").close();
            assertTrue(Cluster.await(() -> Cluster.sees(cluster.node("n0"), PeerState.LEFT, "n2")
                    && Cluster.sees(cluster.node("n1"), PeerState.LEFT, "n2"), 5_000));
        }
    }

    @Test
    void gossipSurvivesLossDelayAndReordering() {
        try (var cluster = new Cluster()) {
            cluster.network.dropRate(0.2).delay(0, 15).reorder(0.3, 30);
            cluster.base = cluster.base.withSuspectTimeoutMs(5_000);
            cluster.add("n0", List.of());
            for (var i = 1; i < 6; i++) {
                cluster.add("n" + i, List.of("n0"));
            }
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 20_000));
        }
    }

    @Test
    void aWrongTokenIsRejected() {
        try (var cluster = new Cluster()) {
            cluster.add("good0", List.of(), "secret", new SwarmHandlers() {});
            cluster.add("good1", List.of("good0"), "secret", new SwarmHandlers() {});
            cluster.add("intruder", List.of("good0"), "guess", new SwarmHandlers() {});
            assertTrue(Cluster.await(() -> Cluster.sees(cluster.node("good1"), PeerState.ALIVE, "good0")
                    && Cluster.sees(cluster.node("good0"), PeerState.ALIVE, "good1"), 5_000));
            assertTrue(Cluster.await(() -> cluster.network.rejected() > 0, 2_000));
            assertFalse(cluster.node("good0").members().containsKey("intruder"));
            assertEquals(1, cluster.node("intruder").members().size());
        }
    }

    @Test
    void theSwarmSizeIsLimited() {
        try (var cluster = new Cluster()) {
            cluster.base = cluster.base.withMaxPeers(4);
            cluster.add("n0", List.of());
            for (var i = 1; i < 7; i++) {
                cluster.add("n" + i, List.of("n0"));
            }
            Cluster.await(() -> false, 600);
            var sizes = new ArrayList<Integer>();
            cluster.nodes.forEach(n -> sizes.add(n.members().size()));
            assertTrue(sizes.stream().allMatch(s -> s <= 4), "no table is above 4: " + sizes);
        }
    }

    @Test
    void capabilityChangesSpread() {
        try (var cluster = new Cluster()) {
            cluster.add("n0", List.of());
            cluster.add("n1", List.of("n0"));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));
            cluster.node("n1").updateSelf(List.of("tools:bash"), "repo");
            assertTrue(Cluster.await(() -> cluster.node("n0").findPeer("tools:bash").isPresent(), 5_000));
            assertEquals("repo", cluster.node("n0").members().get("n1").workspace());
        }
    }
}
