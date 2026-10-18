package org.github.lscoughlin.jeorje.swarm;

/** The state of a peer in the membership table. */
public enum PeerState {
    ALIVE(0),
    SUSPECT(1),
    DEAD(2),
    LEFT(3);

    private final int severity;

    PeerState(int severity) {
        this.severity = severity;
    }

    /** At equal incarnation, the state with the higher severity wins. */
    public int severity() {
        return severity;
    }

    /** Returns true when the peer can be contacted. */
    public boolean reachable() {
        return this == ALIVE || this == SUSPECT;
    }
}
