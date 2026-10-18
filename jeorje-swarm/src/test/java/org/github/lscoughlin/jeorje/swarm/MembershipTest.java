package org.github.lscoughlin.jeorje.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import org.github.lscoughlin.jeorje.swarm.Membership.Merge;
import org.github.lscoughlin.jeorje.testkit.ManualClock;
import org.junit.jupiter.api.Test;

class MembershipTest {
    private final ManualClock clock = new ManualClock();
    private final SwarmConfig config = SwarmConfig.defaults();
    private final Membership table = new Membership(PeerRecord.of("me", "mem:me", "me"), config, clock);

    private static PeerRecord peer(String id, long incarnation, PeerState state) {
        return new PeerRecord(id, "mem:" + id, id, incarnation, state, java.util.List.of(), "");
    }

    @Test
    void newPeerIsAdded() {
        assertEquals(Merge.UPDATED, table.merge(peer("a", 0, PeerState.ALIVE)));
        assertEquals(PeerState.ALIVE, table.get("a").state());
    }

    @Test
    void higherIncarnationReplacesLower() {
        table.merge(peer("a", 0, PeerState.ALIVE));
        table.suspect("a");
        assertEquals(PeerState.SUSPECT, table.get("a").state());
        assertEquals(Merge.UPDATED, table.merge(peer("a", 1, PeerState.ALIVE)));
        assertEquals(PeerState.ALIVE, table.get("a").state());
        assertEquals(Merge.IGNORED, table.merge(peer("a", 0, PeerState.DEAD)));
        assertEquals(PeerState.ALIVE, table.get("a").state());
    }

    @Test
    void sameIncarnationWorseStateWins() {
        table.merge(peer("a", 2, PeerState.ALIVE));
        assertEquals(Merge.UPDATED, table.merge(peer("a", 2, PeerState.SUSPECT)));
        assertEquals(Merge.IGNORED, table.merge(peer("a", 2, PeerState.ALIVE)));
        assertEquals(Merge.UPDATED, table.merge(peer("a", 2, PeerState.DEAD)));
        assertEquals(PeerState.DEAD, table.get("a").state());
    }

    @Test
    void suspectBecomesDeadAfterTenSecondsAndIsPurgedAfterFiveMinutes() {
        table.merge(peer("a", 0, PeerState.ALIVE));
        table.suspect("a");
        clock.advance(Duration.ofSeconds(9));
        table.expire();
        assertEquals(PeerState.SUSPECT, table.get("a").state());
        clock.advance(Duration.ofSeconds(2));
        table.expire();
        assertEquals(PeerState.DEAD, table.get("a").state());
        clock.advance(Duration.ofMinutes(4));
        table.expire();
        assertNotNull(table.get("a"));
        clock.advance(Duration.ofMinutes(2));
        table.expire();
        assertNull(table.get("a"));
    }

    @Test
    void oldGossipDoesNotBringADeadPeerBack() {
        table.merge(peer("a", 3, PeerState.ALIVE));
        table.merge(peer("a", 3, PeerState.DEAD));
        assertEquals(Merge.IGNORED, table.merge(peer("a", 3, PeerState.ALIVE)));
        assertEquals(Merge.IGNORED, table.merge(peer("a", 2, PeerState.ALIVE)));
        assertEquals(PeerState.DEAD, table.get("a").state());
    }

    @Test
    void unknownDeadRecordIsIgnored() {
        assertEquals(Merge.IGNORED, table.merge(peer("ghost", 1, PeerState.DEAD)));
        assertNull(table.get("ghost"));
    }

    @Test
    void suspicionAboutSelfIsRefutedWithHigherIncarnation() {
        assertEquals(Merge.REFUTED, table.merge(peer("me", 0, PeerState.SUSPECT)));
        assertEquals(1, table.self().incarnation());
        assertEquals(PeerState.ALIVE, table.self().state());
        assertEquals(Merge.REFUTED, table.merge(peer("me", 5, PeerState.DEAD)));
        assertEquals(6, table.self().incarnation());
        assertEquals(Merge.IGNORED, table.merge(peer("me", 2, PeerState.SUSPECT)));
    }

    @Test
    void sizeLimitStopsNewPeers() {
        var small = new Membership(PeerRecord.of("me", "mem:me", "me"), config.withMaxPeers(3), clock);
        small.merge(peer("a", 0, PeerState.ALIVE));
        small.merge(peer("b", 0, PeerState.ALIVE));
        assertEquals(Merge.IGNORED, small.merge(peer("c", 0, PeerState.ALIVE)));
        assertEquals(3, small.snapshot().size());
    }

    @Test
    void leaveRaisesIncarnationAndWinsAtSameLevel() {
        var left = table.leave();
        assertEquals(PeerState.LEFT, left.state());
        assertEquals(1, left.incarnation());
    }
}
