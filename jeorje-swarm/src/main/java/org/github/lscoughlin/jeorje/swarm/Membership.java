package org.github.lscoughlin.jeorje.swarm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The membership table of one peer. It follows the SWIM rules. The table has one owner thread: the caller
 * must not use an instance from two threads. Other threads read the immutable {@link #snapshot()}.
 */
public final class Membership {
    /** The result of a merge. */
    public enum Merge {
        /** The record changed nothing. */
        IGNORED,
        /** The table changed. */
        UPDATED,
        /** The record said that this peer is suspect or dead. The peer raised its incarnation. */
        REFUTED
    }

    private final String selfId;
    private final SwarmConfig config;
    private final Clock clock;
    private final Map<String, PeerRecord> table = new LinkedHashMap<>();
    private final Map<String, Instant> since = new HashMap<>();

    public Membership(PeerRecord self, SwarmConfig config, Clock clock) {
        this.selfId = self.peerId();
        this.config = config;
        this.clock = clock;
        table.put(selfId, self.withState(PeerState.ALIVE));
    }

    public PeerRecord self() {
        return table.get(selfId);
    }

    public PeerRecord get(String peerId) {
        return table.get(peerId);
    }

    /** Returns an immutable copy of the table. */
    public Map<String, PeerRecord> snapshot() {
        return Map.copyOf(table);
    }

    /** Returns the records of all other peers. */
    public List<PeerRecord> others() {
        var list = new ArrayList<PeerRecord>();
        for (var record : table.values()) {
            if (!record.peerId().equals(selfId)) {
                list.add(record);
            }
        }
        return list;
    }

    /** Merges a record from another peer, following the SWIM precedence rules. */
    public Merge merge(PeerRecord incoming) {
        if (incoming.peerId().equals(selfId)) {
            return mergeSelf(incoming);
        }
        var current = table.get(incoming.peerId());
        if (current == null) {
            if (!incoming.state().reachable() || table.size() >= config.maxPeers()) {
                return Merge.IGNORED;
            }
            put(incoming);
            return Merge.UPDATED;
        }
        if (incoming.newerThan(current.incarnation(), current.state())) {
            put(incoming);
            return Merge.UPDATED;
        }
        return Merge.IGNORED;
    }

    private Merge mergeSelf(PeerRecord incoming) {
        var self = self();
        if (self.state() == PeerState.LEFT) {
            return Merge.IGNORED;
        }
        var accusation = incoming.state() == PeerState.SUSPECT || incoming.state() == PeerState.DEAD;
        if (accusation && incoming.incarnation() >= self.incarnation()) {
            table.put(selfId, self.withIncarnation(incoming.incarnation() + 1).withState(PeerState.ALIVE));
            return Merge.REFUTED;
        }
        return Merge.IGNORED;
    }

    private void put(PeerRecord record) {
        table.put(record.peerId(), record);
        if (record.state() == PeerState.ALIVE) {
            since.remove(record.peerId());
        } else {
            since.put(record.peerId(), clock.instant());
        }
    }

    /** Marks an alive peer as suspect. Returns the new record, or null when nothing changed. */
    public PeerRecord suspect(String peerId) {
        var current = table.get(peerId);
        if (current == null || current.state() != PeerState.ALIVE || peerId.equals(selfId)) {
            return null;
        }
        var next = current.withState(PeerState.SUSPECT);
        put(next);
        return next;
    }

    /** Changes the capabilities or the workspace of this peer and raises the incarnation. */
    public PeerRecord updateSelf(List<String> capabilities, String workspace) {
        var self = self().withCapabilities(capabilities).withWorkspace(workspace);
        self = self.withIncarnation(self.incarnation() + 1);
        table.put(selfId, self);
        return self;
    }

    /** Marks this peer as LEFT with a higher incarnation. */
    public PeerRecord leave() {
        var self = self();
        var next = self.withIncarnation(self.incarnation() + 1).withState(PeerState.LEFT);
        table.put(selfId, next);
        return next;
    }

    /** Moves old suspect records to dead and removes old dead and left records. Returns true on change. */
    public boolean expire() {
        var now = clock.instant();
        var changed = false;
        var suspectLimit = Duration.ofMillis(config.suspectTimeoutMs());
        var deadLimit = Duration.ofMillis(config.deadRetentionMs());
        for (var record : List.copyOf(table.values())) {
            if (record.peerId().equals(selfId)) {
                continue;
            }
            var start = since.get(record.peerId());
            if (start == null) {
                continue;
            }
            var age = Duration.between(start, now);
            switch (record.state()) {
                case SUSPECT -> {
                    if (age.compareTo(suspectLimit) >= 0) {
                        put(record.withState(PeerState.DEAD));
                        changed = true;
                    }
                }
                case DEAD, LEFT -> {
                    if (age.compareTo(deadLimit) >= 0) {
                        table.remove(record.peerId());
                        since.remove(record.peerId());
                        changed = true;
                    }
                }
                case ALIVE -> { }
            }
        }
        return changed;
    }
}
