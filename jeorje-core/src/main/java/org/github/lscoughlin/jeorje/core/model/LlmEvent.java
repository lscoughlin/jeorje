package org.github.lscoughlin.jeorje.core.model;

/** An event in a streamed model response. */
public sealed interface LlmEvent {
    /** A fragment of text. */
    record TextDelta(String text) implements LlmEvent {}

    /** The model starts a tool call. */
    record ToolUseStart(String id, String tool) implements LlmEvent {}

    /** A fragment of the JSON input text of a tool call. */
    record ToolUseInput(String id, String jsonFragment) implements LlmEvent {}

    /**
     * The message ends.
     *
     * @param stopReason the finish reason of the provider, for example {@code stop}, {@code tool_calls}, or
     *     {@code length}. The value {@code length} means that the model used its output limit.
     * @param reasoningChars the characters of the reasoning deltas of the stream. The agent does not show them.
     */
    record MessageStop(String stopReason, int reasoningChars) implements LlmEvent {
        /** A stop with no reasoning text. */
        public MessageStop(String stopReason) {
            this(stopReason, 0);
        }

        /** True when the model used its whole output limit. */
        public boolean outputLimitReached() {
            return LENGTH.equals(stopReason);
        }
    }

    /** The finish reason that means that the model used its output limit. */
    String LENGTH = "length";

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
