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
 *
 * <p>Providers stream the fragments of a tool call in different ways. The assembler keeps one internal key for
 * each call and finds the key of a fragment in this order: the id of the call, when the fragment has one; the
 * index of the call; the call of the last fragment, when the fragment has neither. A fragment with a new id and
 * an index that another call already uses starts a new call. The standard style has the id on the first chunk of
 * a call only. Some providers repeat the id and the name on every chunk. Some send every call with the index 0 and
 * a different id.
 */
final class StreamAssembler {
    /** The id of a call whose id never arrived. */
    static final String LOST_ID_PREFIX = "call-lost-";
    /** The name of a call whose name never arrived. */
    static final String LOST_NAME = "unknown";

    private final ObjectMapper mapper;
    private final Map<Integer, String> ids = new HashMap<>();
    private final Map<String, Integer> idToKey = new HashMap<>();
    private final Map<Integer, Integer> indexToKey = new HashMap<>();
    /** Fragments that arrived before the id and the name were known. */
    private final Map<Integer, StringBuilder> pending = new HashMap<>();
    private final Map<Integer, String> names = new HashMap<>();
    private final java.util.Set<Integer> started = new java.util.HashSet<>();
    private int nextKey;
    private int lastKey = -1;
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

    /** The internal key of the call that a fragment belongs to. */
    private int keyOf(JsonNode call, String id) {
        var hasIndex = call.has("index");
        var index = call.path("index").asInt(0);
        int key;
        if (!id.isEmpty()) {
            var known = idToKey.get(id);
            if (known != null) {
                key = known;
            } else {
                var sameIndex = hasIndex ? indexToKey.get(index) : null;
                if (sameIndex != null && (!ids.containsKey(sameIndex) || ids.get(sameIndex).equals(id))) {
                    key = sameIndex;
                } else {
                    key = nextKey++;
                }
                idToKey.put(id, key);
            }
            if (hasIndex) indexToKey.put(index, key);
        } else if (hasIndex) {
            var mapped = indexToKey.get(index);
            if (mapped == null) {
                mapped = nextKey++;
                indexToKey.put(index, mapped);
            }
            key = mapped;
        } else {
            key = lastKey >= 0 ? lastKey : nextKey++;
        }
        lastKey = key;
        return key;
    }

    /** The text of the arguments of a fragment: a string as it is, an object or array as JSON text. */
    private static String argumentsOf(JsonNode function) {
        var arguments = function.get("arguments");
        if (arguments == null || arguments.isNull()) return "";
        if (arguments.isString()) return arguments.asString();
        return arguments.toString();
    }

    private void toolCall(JsonNode call, List<LlmEvent> events) {
        var function = call.path("function");
        var id = call.path("id").asString("");
        var name = function.path("name").asString("");
        var args = argumentsOf(function);
        var key = keyOf(call, id);
        if (!id.isEmpty()) ids.putIfAbsent(key, id);
        if (!name.isEmpty()) names.putIfAbsent(key, name);
        if (!started.contains(key)) {
            if (!ids.containsKey(key) || !names.containsKey(key)) {
                // The id or the name is not known yet. Keep the fragment.
                if (!args.isEmpty()) pending.computeIfAbsent(key, k -> new StringBuilder()).append(args);
                return;
            }
            started.add(key);
            events.add(new LlmEvent.ToolUseStart(ids.get(key), names.get(key)));
            var buffered = pending.remove(key);
            if (buffered != null && !buffered.isEmpty()) {
                events.add(new LlmEvent.ToolUseInput(ids.get(key), buffered.toString()));
            }
        }
        if (!args.isEmpty()) events.add(new LlmEvent.ToolUseInput(ids.get(key), args));
    }

    /**
     * The events for calls that never got an id or a name. The agent must see them, so that it can report a broken
     * call. A call without an id gets the id {@code call-lost-<n>}. A call without a name gets the name
     * {@code unknown}. Call this before {@link #finish()}.
     */
    List<LlmEvent> flush() {
        var events = new ArrayList<LlmEvent>();
        for (var key = 0; key < nextKey; key++) {
            if (started.contains(key)) continue;
            var buffered = pending.remove(key);
            var hasId = ids.containsKey(key);
            var hasName = names.containsKey(key);
            if (!hasId && !hasName && (buffered == null || buffered.isEmpty())) continue;
            var id = hasId ? ids.get(key) : LOST_ID_PREFIX + key;
            started.add(key);
            events.add(new LlmEvent.ToolUseStart(id, hasName ? names.get(key) : LOST_NAME));
            if (buffered != null && !buffered.isEmpty()) events.add(new LlmEvent.ToolUseInput(id, buffered.toString()));
        }
        return events;
    }

    /** The final event of the stream. */
    LlmEvent finish() {
        return new LlmEvent.MessageStop(finishReason == null ? "stop" : finishReason);
    }
}
