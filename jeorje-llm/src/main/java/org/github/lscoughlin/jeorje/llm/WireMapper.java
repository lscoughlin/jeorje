package org.github.lscoughlin.jeorje.llm;

import java.util.ArrayList;
import org.github.lscoughlin.jeorje.core.model.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Maps an {@link LlmRequest} to the Chat Completions wire format. */
final class WireMapper {

    private final ObjectMapper mapper;

    WireMapper(ObjectMapper mapper) { this.mapper = mapper; }

    ObjectNode request(String model, LlmRequest request) {
        return request(model, request, java.util.Map.of());
    }

    /** Maps the request and adds the extra fields at the top level. The fields that Jeorje sets cannot be replaced. */
    ObjectNode request(String model, LlmRequest request, java.util.Map<String, Object> extraBody) {
        var root = request0(model, request);
        extraBody.forEach((key, value) -> {
            if (!org.github.lscoughlin.jeorje.core.config.JeorjeConfig.RESERVED_BODY_KEYS.contains(key)) {
                root.set(key, mapper.valueToTree(value));
            }
        });
        return root;
    }

    private ObjectNode request0(String model, LlmRequest request) {
        var root = mapper.createObjectNode();
        root.put("model", model);
        root.put("stream", true);
        root.putObject("stream_options").put("include_usage", true);
        if (request.maxOutputTokens() > 0) root.put("max_tokens", request.maxOutputTokens());
        var messages = root.putArray("messages");
        if (request.system() != null && !request.system().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.system());
        }
        for (var message : request.messages()) addMessage(messages, message);
        if (!request.tools().isEmpty()) {
            var tools = root.putArray("tools");
            for (var tool : request.tools()) {
                var function = tools.addObject().put("type", "function").putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                var schema = tool.inputSchema();
                function.set("parameters", schema != null ? schema
                        : mapper.createObjectNode().put("type", "object"));
            }
        }
        return root;
    }

    private void addMessage(ArrayNode out, Message message) {
        switch (message.role()) {
            case USER -> {
                // Tool results come first, because they answer the tool calls of the last assistant message.
                var texts = new ArrayList<String>();
                for (var part : message.content()) {
                    switch (part) {
                        case ToolResult r -> out.addObject().put("role", "tool")
                                .put("tool_call_id", r.toolUseId()).put("content", r.output());
                        case Text t -> texts.add(t.text());
                        case ToolUse u -> { /* A user message has no tool use. */ }
                    }
                }
                if (!texts.isEmpty()) out.addObject().put("role", "user").put("content", String.join("\n", texts));
            }
            case ASSISTANT -> {
                var texts = new ArrayList<String>();
                var calls = new ArrayList<ToolUse>();
                for (var part : message.content()) {
                    switch (part) {
                        case Text t -> texts.add(t.text());
                        case ToolUse u -> calls.add(u);
                        case ToolResult r -> { /* An assistant message has no tool result. */ }
                    }
                }
                var node = out.addObject().put("role", "assistant");
                if (texts.isEmpty()) node.putNull("content"); else node.put("content", String.join("", texts));
                if (!calls.isEmpty()) {
                    var array = node.putArray("tool_calls");
                    for (var call : calls) {
                        var item = array.addObject();
                        item.put("id", call.id()).put("type", "function");
                        var function = item.putObject("function");
                        function.put("name", call.tool());
                        JsonNode input = call.input();
                        function.put("arguments", input == null || input.isNull() ? "{}" : input.toString());
                    }
                }
            }
        }
    }

}
