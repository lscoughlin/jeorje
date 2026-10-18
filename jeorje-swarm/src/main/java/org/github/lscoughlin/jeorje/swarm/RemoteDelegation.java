package org.github.lscoughlin.jeorje.swarm;

import java.util.concurrent.CompletableFuture;

/**
 * The local handle of a task that runs in a remote subagent. Events go to the listener that was given at
 * the start. The {@link #completion()} future finishes with the last event, which has the completion summary.
 * It finishes with an error when the peer is lost.
 */
public interface RemoteDelegation {
    String taskId();

    String peerId();

    /** Sends steering text to the remote subagent. */
    void steer(String text);

    /** Asks the remote subagent to stop. */
    void cancel();

    /** Answers a question that the remote subagent sent. */
    void answer(String questionId, String text);

    /** The last event of the task. */
    CompletableFuture<DelegateEvent> completion();
}
