package org.github.lscoughlin.jeorje.core.model;

/** An event that an agent publishes to the UI. */
public sealed interface AgentEvent {
    AgentId agent();

    record TokenDelta(AgentId agent, String text) implements AgentEvent {}

    record ToolStarted(AgentId agent, String toolUseId, String tool, String summary) implements AgentEvent {}

    record ToolFinished(AgentId agent, String toolUseId, ToolResultData result) implements AgentEvent {}

    /** The agent waits for the user to approve a tool call. */
    record ConfirmationRequested(AgentId agent, ToolUse toolUse) implements AgentEvent {}

    record TurnFinished(AgentId agent, int turn) implements AgentEvent {}

    record Compacted(AgentId agent, int chunksPruned, int tokensFreed) implements AgentEvent {}

    /**
     * The request of the agent did not fit the budget, so recent turns were pruned or left the request.
     *
     * @param droppedTurns the numbers of the turns that left the request
     * @param prunedChunks the number of chunks that the agent pruned to make room
     * @param overBudget true when the request is still over the budget
     */
    record ContextTrimmed(AgentId agent, java.util.List<Integer> droppedTurns, int prunedChunks, boolean overBudget)
            implements AgentEvent {}

    /**
     * The size of the request of the last model call, as estimated tokens and the budget of the working context.
     * The status line shows the share.
     */
    record ContextUsage(AgentId agent, int tokens, int budget) implements AgentEvent {}

    /**
     * A subagent used most of its turn limit without progress and asked the advisor for help.
     *
     * @param turns the model calls since the last progress
     * @param limit the turn limit of the subagent
     */
    record HelpRequested(AgentId agent, int turns, int limit) implements AgentEvent {}

    /**
     * The state of the model call of an agent. The agent publishes it when a call starts, while the answer streams
     * (at most about four times each second), when the provider counts arrive, and when the call ends or fails.
     * A retry after a failed call keeps the state active.
     *
     * @param active true while a model call runs, false when the call ended, failed, or was cancelled
     * @param tokensUp the tokens sent in the model calls of the running turn. It starts as the estimate of the
     *                 request and becomes the count of the provider.
     * @param tokensDown the tokens received in the model calls of the running turn. It is an estimate while the
     *                   answer streams and becomes the count of the provider.
     */
    record Working(AgentId agent, boolean active, int tokensUp, int tokensDown) implements AgentEvent {}

    record Error(AgentId agent, String message) implements AgentEvent {}

    /**
     * The user steered the turn of the agent with a text.
     *
     * @param running true when a turn runs and takes the text at its next step boundary
     */
    record Steered(AgentId agent, String text, boolean running) implements AgentEvent {}

    /** The agent started a fresh conversation and forgot {@code turns} finished turns. */
    record Cleared(AgentId agent, int turns) implements AgentEvent {}
}
