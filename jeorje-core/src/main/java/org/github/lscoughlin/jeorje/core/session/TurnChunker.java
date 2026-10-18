package org.github.lscoughlin.jeorje.core.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.model.Content;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.model.Turn;

/**
 * Converts a finished turn to chunks. A turn is one chunk by default. A tool result of more than
 * {@link #MAX_RESULT_CHARS} characters becomes separate chunks, split at paragraph or line boundaries
 * with 10% overlap. A {@code recall} call becomes a reference chunk: the query, the filters, and the
 * ids of the returned chunks, never the recalled text.
 */
public final class TurnChunker {
    /** The size limit of a tool result before it is split. */
    public static final int MAX_RESULT_CHARS = 8_000;
    /** The name of the recall tool. */
    public static final String RECALL = "recall";

    private static final Pattern CHUNK_ID = Pattern.compile("[A-Za-z0-9_-]+(?:[.-][A-Za-z0-9_-]+)*:\\d+\\.\\d+");

    private final String sessionId;
    private final int maxChars;

    public TurnChunker(String sessionId) {
        this(sessionId, MAX_RESULT_CHARS);
    }

    public TurnChunker(String sessionId, int maxChars) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (maxChars < 10) throw new IllegalArgumentException("maxChars too small");
        this.maxChars = maxChars;
    }

    /** Chunks one turn. Parts are numbered from 0 in the order of the returned list. */
    public List<Chunk> chunk(Turn turn, Instant timestamp) {
        var toolNames = new java.util.HashMap<String, String>();
        var toolUses = new java.util.HashMap<String, ToolUse>();
        for (var m : turn.messages()) {
            for (var c : m.content()) {
                if (c instanceof ToolUse tu) {
                    toolNames.put(tu.id(), tu.tool());
                    toolUses.put(tu.id(), tu);
                }
            }
        }
        var main = new StringBuilder();
        var kinds = new LinkedHashSet<String>();
        var mainTools = new LinkedHashSet<String>();
        var recalls = new ArrayList<String>();
        var large = new ArrayList<ToolResult>();
        for (var m : turn.messages()) {
            for (var c : m.content()) {
                switch (c) {
                    case Text t -> {
                        var user = m.role() == Role.USER;
                        kinds.add(user ? "user" : "assistant");
                        line(main, (user ? "USER: " : "ASSISTANT: ") + t.text());
                    }
                    case ToolUse tu when RECALL.equals(tu.tool()) -> { }
                    case ToolUse tu -> {
                        kinds.add("tool_call");
                        mainTools.add(tu.tool());
                        line(main, "TOOL_CALL " + tu.tool() + " " + (tu.input() == null ? "{}" : tu.input()));
                    }
                    case ToolResult tr when RECALL.equals(toolNames.get(tr.toolUseId())) ->
                        recalls.add(recallReference(toolUses.get(tr.toolUseId()), tr));
                    case ToolResult tr when tr.output().length() > maxChars -> large.add(tr);
                    case ToolResult tr -> {
                        kinds.add("tool_result");
                        var name = toolNames.get(tr.toolUseId());
                        if (name != null) mainTools.add(name);
                        line(main, "TOOL_RESULT " + (name == null ? "" : name + " ") + tr.output());
                    }
                }
            }
        }
        var out = new ArrayList<Chunk>();
        var agent = turn.agent().value();
        if (!main.isEmpty()) {
            var kind = kinds.size() == 1 ? kinds.iterator().next() : "mixed";
            var tool = mainTools.size() == 1 ? mainTools.iterator().next() : null;
            out.add(make(agent, turn.number(), out.size(), timestamp, kind, tool, main.toString().stripTrailing()));
        }
        for (var ref : recalls) {
            out.add(make(agent, turn.number(), out.size(), timestamp, "tool_call", RECALL, ref));
        }
        for (var tr : large) {
            var name = toolNames.get(tr.toolUseId());
            for (var piece : split(tr.output())) {
                out.add(make(agent, turn.number(), out.size(), timestamp, "tool_result", name, piece));
            }
        }
        return out;
    }

    /** Splits a text at paragraph or line boundaries. Each piece has at most {@code maxChars} characters. */
    public List<String> split(String text) {
        var pieces = new ArrayList<String>();
        if (text.length() <= maxChars) {
            pieces.add(text);
            return pieces;
        }
        var overlap = maxChars / 10;
        var start = 0;
        while (true) {
            var end = Math.min(text.length(), start + maxChars);
            if (end < text.length()) end = boundary(text, start, end);
            pieces.add(text.substring(start, end));
            if (end >= text.length()) break;
            start = Math.max(end - overlap, start + 1);
        }
        return pieces;
    }

    private int boundary(String text, int start, int end) {
        var floor = start + maxChars / 2;
        var para = text.lastIndexOf("\n\n", end - 2);
        if (para >= floor) return para + 2;
        var nl = text.lastIndexOf('\n', end - 1);
        if (nl >= floor) return nl + 1;
        return end;
    }

    private String recallReference(ToolUse use, ToolResult result) {
        var sb = new StringBuilder("RECALL");
        var input = use == null ? null : use.input();
        if (input != null) {
            if (input.has("query")) sb.append(" query=").append(input.get("query").asString());
            if (input.has("chunkId")) sb.append(" chunkId=").append(input.get("chunkId").asString());
            if (input.has("filters")) sb.append(" filters=").append(input.get("filters"));
        }
        var ids = new LinkedHashSet<String>();
        var m = CHUNK_ID.matcher(result.output());
        while (m.find()) ids.add(m.group());
        sb.append(" returned=").append(String.join(",", ids));
        return sb.toString();
    }

    private Chunk make(String agent, int turn, int part, Instant ts, String kind, String tool, String text) {
        return new Chunk(agent + ":" + turn + "." + part, sessionId, agent, turn, part, ts, kind, tool, text);
    }

    private static void line(StringBuilder sb, String s) {
        sb.append(s).append('\n');
    }
}
