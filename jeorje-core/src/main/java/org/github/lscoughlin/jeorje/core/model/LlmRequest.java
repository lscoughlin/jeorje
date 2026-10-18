package org.github.lscoughlin.jeorje.core.model;

import java.util.List;

/** A request to the model. */
public record LlmRequest(String system, List<Message> messages,
                         List<ToolDefinition> tools, int maxOutputTokens) {
    public LlmRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }
}
