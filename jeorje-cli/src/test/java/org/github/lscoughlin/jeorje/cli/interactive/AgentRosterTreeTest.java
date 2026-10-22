package org.github.lscoughlin.jeorje.cli.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.jeorje.core.directory.RosterKind;
import org.github.lscoughlin.jeorje.core.directory.RosterNode;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.swarm.PeerRecord;
import org.github.lscoughlin.jeorje.swarm.SwarmHandlers.PeerAgent;
import org.junit.jupiter.api.Test;

class AgentRosterTreeTest {

    private static AgentRoster roster(Map<String, List<PeerAgent>> remote, List<PeerRecord> peers) {
        var hub = new SessionHub();
        hub.add(MainSession.first(), null);
        return new AgentRoster(hub, List::of, () -> peers, session -> "feature/x",
                id -> List.of(), (id, text) -> { }, id -> remote.getOrDefault(id, List.of()), () -> "work");
    }

    @Test
    void theTreeHasThreeLevelsWithTheRightParentLinks() {
        var peers = List.of(PeerRecord.of("p1", "127.0.0.1:1", "alpha"), PeerRecord.of("p2", "127.0.0.1:2", "beta"));
        var remote = Map.of(
                "p1", List.of(new PeerAgent("main", "main", "", "IDLE"), new PeerAgent("w1", "worker", "main", "RUNNING"),
                        new PeerAgent("w2", "helper", "w1", "RUNNING")),
                "p2", List.of(new PeerAgent("main", "main", "", "RUNNING")));
        var tree = roster(remote, peers).tree();

        assertEquals(3, tree.size());
        var local = tree.get(0);
        assertEquals(RosterKind.HARNESS, local.kind());
        assertEquals("work", local.label());
        assertEquals(List.of("main"), local.children().stream().map(RosterNode::id).toList());
        assertEquals(RosterKind.AGENT, local.children().getFirst().kind());

        var alpha = tree.get(1);
        assertEquals(RosterKind.HARNESS, alpha.kind());
        assertEquals("alpha", alpha.label());
        assertEquals("peer:p1", alpha.id());
        assertEquals(1, alpha.children().size());
        var main = alpha.children().getFirst();
        assertEquals(RosterKind.AGENT, main.kind());
        assertEquals("peer:p1/main", main.id());
        assertEquals(1, main.children().size());
        var worker = main.children().getFirst();
        assertEquals(RosterKind.SUBAGENT, worker.kind());
        assertEquals("peer:p1/w1", worker.id());
        assertEquals(List.of("peer:p1/w2"), worker.children().stream().map(RosterNode::id).toList());
        assertEquals(RosterKind.SUBAGENT, worker.children().getFirst().kind());

        var beta = tree.get(2);
        assertEquals("beta", beta.label());
        assertEquals(List.of("peer:p2/main"), beta.children().stream().map(RosterNode::id).toList());
        assertTrue(beta.children().getFirst().children().isEmpty());
    }

    @Test
    void anAgentWithAnUnknownParentCountsAsARootAgent() {
        var peers = List.of(PeerRecord.of("p1", "127.0.0.1:1", "alpha"));
        var remote = Map.of("p1", List.of(new PeerAgent("w9", "orphan", "gone", "IDLE")));
        var alpha = roster(remote, peers).tree().get(1);
        assertEquals(RosterKind.AGENT, alpha.children().getFirst().kind());
    }

    @Test
    void thePeerRostersAreCachedAndFetchedInTheBackground() {
        var calls = new AtomicInteger();
        var now = new long[] {1000};
        var rosters = new RemoteRosters(peerId -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(List.of(new PeerAgent("main", "main", "", "IDLE")));
        }, () -> now[0], 500, null);
        // The first read starts the fetch. The fetch is already done here, so the second read sees the agents.
        assertTrue(rosters.get("p1").isEmpty());
        assertEquals(1, rosters.get("p1").size());
        assertEquals(1, calls.get());
        now[0] += 1000;
        assertEquals(1, rosters.get("p1").size());
        assertEquals(2, calls.get());
    }

    @Test
    void aFailedFetchKeepsTheOldListAndDoesNotThrow() {
        var fail = new boolean[1];
        var rosters = new RemoteRosters(peerId -> fail[0]
                ? CompletableFuture.failedFuture(new IllegalStateException("down"))
                : CompletableFuture.completedFuture(List.of(new PeerAgent("main", "main", "", "IDLE"))),
                () -> 0L, -1, null);
        rosters.get("p1");
        assertEquals(1, rosters.get("p1").size());
        fail[0] = true;
        assertEquals(1, rosters.get("p1").size());
        assertEquals(1, rosters.get("p1").size());
    }
}
