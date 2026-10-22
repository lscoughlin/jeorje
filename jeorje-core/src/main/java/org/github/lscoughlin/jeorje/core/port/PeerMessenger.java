package org.github.lscoughlin.jeorje.core.port;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The port through which an agent reaches an agent on another instance of the swarm. The swarm module
 * implements it over SSH. A message or a question never approves a tool run on the other instance.
 */
public interface PeerMessenger {

    /**
     * Sends text to an agent on a peer. No answer is expected.
     *
     * @throws RuntimeException when the peer is unknown or cannot be reached; the message tells why
     */
    void message(String peerId, String agent, String text);

    /**
     * Asks a question of an agent on a peer. The future completes with the answer. It fails with the reason
     * when the peer refuses, does not answer in time, or cannot be reached. The call itself does not throw.
     */
    CompletableFuture<String> ask(String peerId, String agent, String question);

    /** The ids of the peers that can be reached now. The tools list them in an error for an unknown id. */
    default List<String> peerIds() {
        return List.of();
    }
}
