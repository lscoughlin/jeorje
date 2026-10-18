package org.github.lscoughlin.jeorje.core.subagent;

import java.nio.file.Path;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;

/**
 * The hook for the completion sequence of a subagent. The worktree story implements it for the states
 * {@code SYNCING}, {@code RESOLVING}, and {@code MERGING}. The runtime calls it on the thread of the subagent
 * after the work ended with the outcome {@code completed}. The default implementation does nothing.
 */
@FunctionalInterface
public interface CompletionSteps {

    /** Runs the sequence. The implementation reports each phase with {@link Context#enter}. */
    Result run(Context context) throws Exception;

    /** The default: no steps. */
    static CompletionSteps none() {
        return context -> Result.skipped();
    }

    /**
     * The result of the sequence.
     *
     * @param success false if the sequence failed. The subagent then ends with the outcome {@code failed}.
     * @param branch text for the {@code Branch} part of the summary, or null
     * @param failure the reason when it failed
     */
    record Result(boolean success, String branch, String failure) {
        public static Result skipped() { return new Result(true, null, null); }

        public static Result ok(String branch) { return new Result(true, branch, null); }

        public static Result failed(String reason, String branch) { return new Result(false, branch, reason); }
    }

    /** What the runtime gives to the steps. */
    interface Context {
        AgentId agent();

        String name();

        Path workingDirectory();

        /** The isolation that the spawn asked for, or null. */
        String isolation();

        CancellationToken cancel();

        /** Moves the agent to a phase: {@code SYNCING}, {@code RESOLVING}, or {@code MERGING}. */
        void enter(AgentLifecycle phase);

        /**
         * Registers an internal wait, for example for an integration lock. Agents that ask this agent get the
         * error {@code busy}. The wait counts as an edge in the deadlock check when {@code holder} is not null.
         * Close the result when the wait ends.
         */
        AutoCloseable internalWait(AgentId holder, String reason);

        /** Puts a message in the inbox of the agent and runs the agent loop until it ends its turn. */
        void resumeWork(String message);

        /**
         * Like {@link #resumeWork(String)}, but the agent may make at most {@code maxModelCalls} model calls.
         *
         * @return the number of model calls that the agent made. A value equal to {@code maxModelCalls} means
         *         that the budget is used up. An implementation that cannot count returns 0.
         */
        default int resumeWork(String message, int maxModelCalls) {
            resumeWork(message);
            return 0;
        }
    }
}
