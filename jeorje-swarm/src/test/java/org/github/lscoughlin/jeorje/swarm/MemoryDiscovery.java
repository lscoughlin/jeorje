package org.github.lscoughlin.jeorje.swarm;

import java.util.List;
import java.util.Map;

/** A discovery that shares one map, in place of the network. */
final class MemoryDiscovery implements PeerDiscovery {
    private final Map<String, Entry> shared;

    MemoryDiscovery(Map<String, Entry> shared) {
        this.shared = shared;
    }

    @Override
    public void announce(PeerRecord self) {
        shared.put(self.peerId(), new Entry(self.peerId(), self.address(), self.name()));
    }

    @Override
    public void withdraw(String peerId) {
        shared.remove(peerId);
    }

    @Override
    public List<Entry> peers(String exceptPeerId) {
        return shared.values().stream().filter(e -> !e.peerId().equals(exceptPeerId)).toList();
    }
}
