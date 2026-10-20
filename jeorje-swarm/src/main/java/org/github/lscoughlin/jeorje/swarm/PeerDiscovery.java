package org.github.lscoughlin.jeorje.swarm;

import java.util.List;

/**
 * Finds other instances on the network before the swarm knows them. An implementation announces this
 * instance and lists the instances that it has seen. The swarm uses the listed addresses as extra WKA
 * entries when it has no reachable peer.
 */
public interface PeerDiscovery {

    /** One instance that discovery has seen. */
    record Entry(String peerId, String address, String name) {}

    /** Starts to announce this instance. */
    void announce(PeerRecord self);

    /** Stops the announcement and releases the resources of discovery. */
    void withdraw(String peerId);

    /** The instances seen so far, except the one with the given peer id. */
    List<Entry> peers(String exceptPeerId);

    /** The addresses of the instances seen so far, except the one with the given peer id. */
    default List<String> addresses(String exceptPeerId) {
        return peers(exceptPeerId).stream().map(Entry::address).toList();
    }
}
