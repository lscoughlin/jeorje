package org.github.lscoughlin.jeorje.swarm;

import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Joins several discoveries. It announces to all of them and merges what they see, one entry per peer id. */
public final class CompositeDiscovery implements PeerDiscovery {
    private static final Logger log = LoggerFactory.getLogger(CompositeDiscovery.class);

    private final List<PeerDiscovery> parts;

    public CompositeDiscovery(List<PeerDiscovery> parts) {
        this.parts = List.copyOf(parts);
    }

    @Override
    public void announce(PeerRecord self) {
        for (var part : parts) {
            try {
                part.announce(self);
            } catch (RuntimeException e) {
                log.atWarn().log(() -> "a discovery cannot announce: " + e.getMessage());
            }
        }
    }

    @Override
    public void withdraw(String peerId) {
        for (var part : parts) {
            try {
                part.withdraw(peerId);
            } catch (RuntimeException e) {
                log.atDebug().log(() -> "a discovery cannot withdraw: " + e.getMessage());
            }
        }
    }

    @Override
    public List<Entry> peers(String exceptPeerId) {
        var merged = new LinkedHashMap<String, Entry>();
        for (var part : parts) {
            for (var entry : part.peers(exceptPeerId)) {
                merged.merge(entry.peerId(), entry, CompositeDiscovery::richer);
            }
        }
        return List.copyOf(merged.values());
    }

    /** Keeps the first entry, and fills a blank SSH address from the second. */
    private static Entry richer(Entry first, Entry second) {
        return first.sshAddress().isBlank() && !second.sshAddress().isBlank()
                ? new Entry(first.peerId(), first.address(), second.sshAddress(), first.name()) : first;
    }
}
