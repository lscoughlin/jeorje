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

    record Error(AgentId agent, String message) implements AgentEvent {}
}
