package org.github.lscoughlin.jeorje.core.model;

import java.util.List;

/**
 * A request to the model.
 *
 * @param cachedPrefixMessages the number of leading messages that stay the same from one request of an agent to
 *                             the next. A negative value means the request does not use the prompt cache. A client
 *                             that needs explicit cache breakpoints puts the last one after these messages.
 */
public record LlmRequest(String system, List<Message> messages, List<ToolDefinition> tools, int maxOutputTokens,
                         int cachedPrefixMessages) {
    public LlmRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }

    /** A request that does not use the prompt cache. */
    public LlmRequest(String system, List<Message> messages, List<ToolDefinition> tools, int maxOutputTokens) {
        this(system, messages, tools, maxOutputTokens, -1);
    }
}
