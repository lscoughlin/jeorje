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

    /** Refuses a question that the remote subagent sent (for example with the reason {@code busy}). */
    void answerError(String questionId, String reason);

    /**
     * Asks the remote subagent a question with a question id. The future finishes with the answer. It finishes with
     * an error when the peer refuses the question (the message is the reason) or when the peer is lost.
     */
    CompletableFuture<String> ask(String questionId, String text);

    /** The last event of the task. */
    CompletableFuture<DelegateEvent> completion();
}
