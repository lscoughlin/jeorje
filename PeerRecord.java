package org.github.lscoughlin.jeorje.swarm;

import java.util.List;

/**
 * The record of one peer.
 *
 * @param peerId the random id of the peer
 * @param address the {@code host:port} address of the peer
 * @param name the display name
 * @param incarnation the incarnation number; a higher number replaces a lower one
 * @param state the state of the peer
 * @param capabilities capabilities such as {@code tools:bash} or {@code model:<slug>}
 * @param workspace the workspace label, for example the repository name
 */
public record PeerRecord(
        String peerId,
        String address,
        String name,
        long incarnation,
        PeerState state,
        List<String> capabilities,
        String workspace) {

    public PeerRecord {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        state = state == null ? PeerState.ALIVE : state;
    }

    public static PeerRecord of(String peerId, String address, String name) {
        return new PeerRecord(peerId, address, name, 0, PeerState.ALIVE, List.of(), "");
    }

    public PeerRecord withState(PeerState newState) {
        return new PeerRecord(peerId, address, name, incarnation, newState, capabilities, workspace);
    }

    public PeerRecord withIncarnation(long newIncarnation) {
        return new PeerRecord(peerId, address, name, newIncarnation, state, capabilities, workspace);
    }

    public PeerRecord withCapabilities(List<String> newCapabilities) {
        return new PeerRecord(peerId, address, name, incarnation, state, newCapabilities, workspace);
    }

    public PeerRecord withWorkspace(String newWorkspace) {
        return new PeerRecord(peerId, address, name, incarnation, state, capabilities, newWorkspace);
    }

    /** Returns true when this record is newer than the given incarnation and state. */
    public boolean newerThan(long otherIncarnation, PeerState otherState) {
        return incarnation > otherIncarnation
                || (incarnation == otherIncarnation && state.severity() > otherState.severity());
    }
}
