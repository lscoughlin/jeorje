package org.github.lscoughlin.jeorje.core.model;

/** An event in a streamed model response. */
public sealed interface LlmEvent {
    /** A fragment of text. */
    record TextDelta(String text) implements LlmEvent {}

    /** The model starts a tool call. */
    record ToolUseStart(String id, String tool) implements LlmEvent {}

    /** A fragment of the JSON input text of a tool call. */
    record ToolUseInput(String id, String jsonFragment) implements LlmEvent {}

    /** The message ends. */
    record MessageStop(String stopReason) implements LlmEvent {}

    /** Token counts of the provider. */
    record Usage(int inputTokens, int outputTokens) implements LlmEvent {}
}
