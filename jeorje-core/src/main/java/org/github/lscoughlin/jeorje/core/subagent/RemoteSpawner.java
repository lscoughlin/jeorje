package org.github.lscoughlin.jeorje.core.subagent;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The port through which the runtime starts a subagent on a peer of the swarm. The swarm module implements it.
 * The runtime shows the remote subagent as a local subagent.
 */
public interface RemoteSpawner {

    /** A peer that can run a task. */
    record Peer(String id, String name) {}

    /** The kind of a remote event. */
    enum Kind { PROGRESS, QUESTION, RESULT, REJECTED, ERROR }

    /**
     * An event of a remote subagent. The last event has {@code last} set, an outcome, and the completion summary.
     *
     * @param outcome in the last event: {@code completed}, {@code failed}, {@code cancelled}, or {@code rejected}
     */
    record Event(Kind kind, String text, String questionId, boolean last, String outcome, String summary) {}

    /** The handle of a task on a peer. */
    interface Task {
        /** Sends steering text to the remote subagent. */
        void steer(String text);

        /** Asks the remote subagent to stop. */
        void cancel();

        /** Answers a question of the remote subagent. */
        void answer(String questionId, String text);

        /** Finishes with the last event, or with an error when the peer is lost. */
        CompletableFuture<Event> completion();
    }

    /**
     * Picks a peer. {@code peer} is an id, a name, or an address. When it is null, {@code capability} picks any
     * live peer that has the capability.
     */
    Optional<Peer> resolve(String peer, String capability);

    /**
     * Starts the task on the peer.
     *
     * @param tools the names of the tools that the remote subagent can use, or an empty list for the default
     * @param listener receives all events, the last one included
     */
    Task start(Peer peer, String task, List<String> tools, int maxModelCalls, int timeoutSeconds,
               Consumer<Event> listener);
}
