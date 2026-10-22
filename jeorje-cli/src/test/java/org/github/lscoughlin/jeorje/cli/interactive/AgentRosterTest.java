package org.github.lscoughlin.jeorje.cli.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.junit.jupiter.api.Test;

/** The hub of sessions and the roster that lists them. */
class AgentRosterTest {
    private static AgentRoster roster(SessionHub hub, List<String> steered) {
        return new AgentRoster(hub, List::of, List::of, session -> "branch-" + session.name(), id -> List.of(),
                (id, text) -> steered.add(id + ":" + text));
    }

    @Test
    void theHubKeepsTheOrderAndTheParents() {
        var hub = new SessionHub();
        var main = MainSession.first();
        var fork = new MainSession(hub.nextForkId(), "build");
        hub.add(main, null);
        hub.add(fork, main.id());
        assertEquals(List.of(main, fork), hub.sessions());
        assertNull(hub.parent(main.id()));
        assertEquals(main.id(), hub.parent(fork.id()));
        assertSame(fork, hub.get(new AgentId("fork-1")));
        assertSame(main, hub.foregroundSession());
        hub.setForeground(fork.id());
        assertSame(fork, hub.foregroundSession());
        assertThrows(IllegalArgumentException.class, () -> hub.add(fork, main.id()));
    }

    @Test
    void forkIdsCountUp() {
        var hub = new SessionHub();
        assertEquals(new AgentId("fork-1"), hub.nextForkId());
        assertEquals(new AgentId("fork-2"), hub.nextForkId());
    }

    @Test
    void theRosterHasOneTopLevelEntryForEachSession() {
        var hub = new SessionHub();
        var main = MainSession.first();
        var fork = new MainSession(hub.nextForkId(), "build");
        hub.add(main, null);
        hub.add(fork, main.id());
        fork.busy().set(true);
        var entries = roster(hub, new CopyOnWriteArrayList<>()).agents();
        assertEquals(2, entries.size());
        assertEquals("main", entries.get(0).name());
        assertNull(entries.get(0).parent());
        assertEquals("IDLE", entries.get(0).state());
        assertEquals("branch-main", entries.get(0).branch());
        assertEquals(new AgentId("fork-1"), entries.get(1).id());
        assertEquals("build", entries.get(1).name());
        assertEquals(main.id(), entries.get(1).parent());
        assertEquals("RUNNING", entries.get(1).state());
    }

    @Test
    void anEndedSessionStaysInTheListAsEnded() {
        var hub = new SessionHub();
        var main = MainSession.first();
        var fork = new MainSession(hub.nextForkId(), "build");
        hub.add(main, null);
        hub.add(fork, main.id());
        fork.end();
        var entries = roster(hub, new CopyOnWriteArrayList<>()).agents();
        assertEquals(2, entries.size());
        assertEquals("ENDED", entries.get(1).state());
    }

    @Test
    void steeringASessionRootGoesToTheSessionSteer() {
        var hub = new SessionHub();
        var main = MainSession.first();
        var fork = new MainSession(hub.nextForkId(), "build");
        hub.add(main, null);
        hub.add(fork, main.id());
        var steered = new CopyOnWriteArrayList<String>();
        var roster = roster(hub, steered);
        roster.steer(fork.id(), "go");
        assertEquals(List.of("fork-1:go"), steered);
        assertEquals(new AgentId("fork-1"), roster.find("build").orElseThrow().id());
        assertEquals(new AgentId("fork-1"), roster.find("2").orElseThrow().id());
    }
}
