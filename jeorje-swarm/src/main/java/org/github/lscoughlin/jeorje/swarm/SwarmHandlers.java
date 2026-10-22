package org.github.lscoughlin.jeorje.swarm;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.swarm.Messages.Delegate;
import org.github.lscoughlin.jeorje.swarm.Messages.HistoryHitView;

/**
 * The callbacks that connect a {@link SwarmNode} to the local agents. All callbacks run on one serial
 * thread, in message order. A callback must not block for a long time. The defaults deny or ignore.
 * A peer message never approves a tool run: no callback here has a permission decision.
 */
public interface SwarmHandlers {

    /** A remote subagent that runs a task on this peer. */
    interface RunningTask {
        /** Steering text from the delegating peer. */
        void steer(String text);

        /** Cancel request from the delegating peer. The task still sends its last event. */
        void cancel();

        /** The answer to a question that the task sent with a {@code QUESTION} event. */
        void answer(String questionId, String text);

        /** The delegating peer refuses a question of the task (for example with the reason {@code busy}). */
        default void answerError(String questionId, String reason) {
            answer(questionId, "[the question was refused: " + reason + "]");
        }

        /**
         * A question from the delegating peer to the task. The stage finishes with the answer, or with an error
         * whose message is the reason of a refusal. The default refuses.
         */
        default CompletionStage<String> ask(String questionId, String text) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("unsupported"));
        }
    }

    /**
     * One agent of this peer, as a remote client sees it in the roster.
     *
     * @param agentId the id of the agent
     * @param name the display name
     * @param parentId the id of the parent agent, or an empty string for a root agent
     * @param state the state name, for example {@code RUNNING}
     */
    record PeerAgent(String agentId, String name, String parentId, String state) {
        public PeerAgent {
            name = name == null ? "" : name;
            parentId = parentId == null ? "" : parentId;
            state = state == null ? "" : state;
        }
    }

    /**
     * A local agent that a remote client has in its foreground. The client reads the history and the live lines,
     * steers the agent, and answers its questions. The remote user acts as the local user for this agent.
     */
    interface ForegroundHost {
        /** The lines of the history of the agent, oldest first. */
        List<String> history();

        /** The id of the agent. */
        String agentId();

        /** Steering text from the client. It acts as text that the user types for the agent. */
        void steer(String text);

        /**
         * The answer of the client to a question of the agent, for example a permission prompt. The request id
         * is the id that the live lines name.
         */
        void answer(String requestId, String text);

        /** Sets the sink for the live lines. A null sink stops the delivery. */
        void onLine(Consumer<String> sink);

        /** Called when the client detaches or is lost. The default does nothing. */
        default void detach() {
            onLine(null);
        }
    }

    /** Thrown by {@link DelegateHost#accept} when the permission policy denies the task. */
    final class DelegateRejectedException extends RuntimeException {
        public DelegateRejectedException(String reason) {
            super(reason);
        }
    }

    /** Runs delegated tasks with the policy and the workspace of this peer. */
    interface DelegateHost {
        /**
         * Asks the own permission policy and starts a subagent.
         *
         * @param fromPeer the delegating peer
         * @param request the request
         * @param sink receives the events of the subagent; the last one has the completion summary
         * @throws DelegateRejectedException when the policy denies the task
         */
        RunningTask accept(String fromPeer, Delegate request, Consumer<DelegateEvent> sink);
    }

    /** A text message from an agent on a peer. */
    default void onAgentMessage(String fromPeer, String toAgent, String text) {}

    /** A question from an agent on a peer. The default fails the answer. */
    default CompletionStage<String> onAgentAsk(String fromPeer, String toAgent, String question) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("no agent can answer"));
    }

    /**
     * Attaches a remote client to a local agent. Returns null when this peer has no such agent or does not allow
     * the attach. The default returns null.
     */
    default ForegroundHost attach(String fromPeer, String agentId) {
        return null;
    }

    /** The agents of this peer that a remote client can attach to. The default is empty. */
    default List<PeerAgent> roster() {
        return List.of();
    }

    /** The host for delegated tasks, or null to reject all delegation. */
    default DelegateHost delegateHost() {
        return null;
    }

    /** Runs a remote history search. An empty result means denied. The default is denied. */
    default Optional<List<HistoryHitView>> onHistoryQuery(String fromPeer, String text, int limit) {
        return Optional.empty();
    }

    /** Called when the state of a peer changes. */
    default void onPeerChange(PeerRecord record) {}
}
