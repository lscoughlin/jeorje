package org.github.lscoughlin.jeorje.swarm;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The client side of a foreground attach: the local user has an agent of a peer in the foreground. The
 * attachment delivers the live lines to the sink that the caller gave. It sends steering text and answers
 * to the peer.
 */
public interface RemoteAttachment extends AutoCloseable {

    /** Completes with the history lines when the host accepts. Fails with the reason when the host refuses. */
    CompletableFuture<List<String>> opened();

    /** Sends steering text for the agent. */
    void steer(String text);

    /** Answers a question of the agent. The stage fails with the reason when the host refuses the answer. */
    CompletableFuture<Void> answer(String requestId, String text);

    /** Detaches. Lines that arrive later are dropped. */
    @Override
    void close();
}
