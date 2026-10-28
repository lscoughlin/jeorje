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

    /**
     * Token counts of the provider.
     *
     * @param inputTokens all prompt tokens, including the cached ones
     * @param cachedTokens the prompt tokens that the provider read from its prompt cache
     * @param cacheWriteTokens the prompt tokens that the provider wrote to its prompt cache
     */
    record Usage(int inputTokens, int outputTokens, int cachedTokens, int cacheWriteTokens) implements LlmEvent {
        /** Token counts of a provider that reports no cache numbers. */
        public Usage(int inputTokens, int outputTokens) {
            this(inputTokens, outputTokens, 0, 0);
        }
    }
}
