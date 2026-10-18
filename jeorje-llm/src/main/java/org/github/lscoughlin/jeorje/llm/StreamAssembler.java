package org.github.lscoughlin.jeorje.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns the JSON chunks of a streamed response into {@link LlmEvent} objects. The usage chunk
 * arrives after the finish reason, so the assembler sends {@link LlmEvent.MessageStop} last, in
 * {@link #finish()}.
 */
final class StreamAssembler {

    private final ObjectMapper mapper;
    /** Tool-call index to id. */
    private final Map<Integer, String> ids = new HashMap<>();
    /** Fragments that arrived before the id and the name were known. */
    private final Map<Integer, StringBuilder> pending = new HashMap<>();
    private final Map<Integer, String> names = new HashMap<>();
    private final java.util.Set<Integer> started = new java.util.HashSet<>();
    private String finishReason;
    private boolean sawData;

    StreamAssembler(ObjectMapper mapper) { this.mapper = mapper; }

    boolean sawData() { return sawData; }

    boolean finished() { return finishReason != null; }

    /** Parses one data payload. Throws {@link LlmException} for an error object. */
    List<LlmEvent> accept(String data) throws LlmException {
        var events = new ArrayList<LlmEvent>();
        JsonNode chunk;
        try {
            chunk = mapper.readTree(data);
        } catch (RuntimeException e) {
            throw new LlmException("Invalid JSON in stream", 0, false);
        }
        sawData = true;
        var error = chunk.get("error");
        if (error != null && !error.isNull()) {
            var message = error.path("message").asString(error.toString());
            throw new LlmException("Model error in stream: " + message, error.path("code").asInt(0), false);
        }
        for (var choice : chunk.path("choices")) {
            var delta = choice.path("delta");
            var content = delta.get("content");
            if (content != null && content.isString() && !content.asString().isEmpty()) {
                events.add(new LlmEvent.TextDelta(content.asString()));
            }
            for (var call : delta.path("tool_calls")) toolCall(call, events);
            var reason = choice.get("finish_reason");
            if (reason != null && reason.isString()) finishReason = reason.asString();
        }
        var usage = chunk.get("usage");
        if (usage != null && usage.isObject()) {
            events.add(new LlmEvent.Usage(usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0)));
        }
        return events;
    }

    private void toolCall(JsonNode call, List<LlmEvent> events) {
        var index = call.path("index").asInt(0);
        var function = call.path("function");
        var id = call.path("id").asString("");
        var name = function.path("name").asString("");
        var args = function.path("arguments").asString("");
        if (!id.isEmpty()) ids.putIfAbsent(index, id);
        if (!name.isEmpty()) names.putIfAbsent(index, name);
        if (!started.contains(index)) {
            if (!ids.containsKey(index) || !names.containsKey(index)) {
                // The id or the name is not known yet. Keep the fragment.
                if (!args.isEmpty()) pending.computeIfAbsent(index, k -> new StringBuilder()).append(args);
                return;
            }
            started.add(index);
            events.add(new LlmEvent.ToolUseStart(ids.get(index), names.get(index)));
            var buffered = pending.remove(index);
            if (buffered != null && !buffered.isEmpty()) {
                events.add(new LlmEvent.ToolUseInput(ids.get(index), buffered.toString()));
            }
        }
        if (!args.isEmpty()) events.add(new LlmEvent.ToolUseInput(ids.get(index), args));
    }

    /** The final event of the stream. */
    LlmEvent finish() {
        return new LlmEvent.MessageStop(finishReason == null ? "stop" : finishReason);
    }
}
