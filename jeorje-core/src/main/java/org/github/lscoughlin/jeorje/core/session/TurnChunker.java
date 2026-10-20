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
import tools.jackson.databind.JsonNode;

/**
 * Converts a finished turn to chunks. A tool result of more than {@link #MAX_RESULT_CHARS} characters becomes
 * separate chunks, split at paragraph or line boundaries with 10% overlap. A {@code recall} call becomes a
 * reference chunk: the query, the filters, and the ids of the returned chunks, never the recalled text.
 *
 * <p>Format 1 (legacy) writes the other parts of a turn as one chunk. Format 2 writes each user text and each
 * {@code ask_user} question with its answer as a separate chunk of the kind {@code user}, and it splits the
 * remaining text of a large turn into parts of at most {@code maxChars} characters. The format of a session
 * is in its manifest, so that a resumed session builds the same chunk ids.
 */
public final class TurnChunker {
    /** The size limit of a tool result before it is split. */
    public static final int MAX_RESULT_CHARS = 8_000;
    /** The name of the recall tool. */
    public static final String RECALL = "recall";
    /** The name of the ask_user tool. */
    public static final String ASK_USER = "ask_user";

    private static final String ANSWER_PREFIX = "User answer: ";
    private static final Pattern CHUNK_ID = Pattern.compile("[A-Za-z0-9_-]+(?:[.-][A-Za-z0-9_-]+)*:\\d+\\.\\d+");

    private final String sessionId;
    private final int maxChars;
    private final int format;

    /** A chunker for the current chunk format. */
    public TurnChunker(String sessionId) {
        this(sessionId, MAX_RESULT_CHARS);
    }

    public TurnChunker(String sessionId, int maxChars) {
        this(sessionId, maxChars, SessionManifest.CURRENT_CHUNK_FORMAT);
    }

    /** A chunker for the given chunk format (1 or 2). */
    public static TurnChunker forFormat(String sessionId, int format) {
        return new TurnChunker(sessionId, MAX_RESULT_CHARS, format);
    }

    public TurnChunker(String sessionId, int maxChars, int format) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (maxChars < 10) throw new IllegalArgumentException("maxChars too small");
        if (format != 1 && format != 2) throw new IllegalArgumentException("unknown chunk format: " + format);
        this.maxChars = maxChars;
        this.format = format;
    }

    /** The chunk format of this chunker. */
    public int format() { return format; }

    /** Chunks one turn. Parts are numbered from 0 in the order of the returned list. */
    public List<Chunk> chunk(Turn turn, Instant timestamp) {
        return format == 1 ? chunkV1(turn, timestamp) : chunkV2(turn, timestamp);
    }

    /** One part of the main text of a turn in format 2. */
    private record Item(String kind, String tool, String text) {}

    private List<Chunk> chunkV2(Turn turn, Instant timestamp) {
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
        var users = new ArrayList<String>();
        var asks = new ArrayList<String>();
        var answered = new java.util.HashSet<String>();
        var main = new ArrayList<Item>();
        var recalls = new ArrayList<String>();
        var large = new ArrayList<ToolResult>();
        for (var m : turn.messages()) {
            for (var c : m.content()) {
                switch (c) {
                    case Text t when m.role() == Role.USER -> users.add("USER: " + t.text());
                    case Text t -> main.add(new Item("assistant", null, "ASSISTANT: " + t.text()));
                    case ToolUse tu when RECALL.equals(tu.tool()) || ASK_USER.equals(tu.tool()) -> { }
                    case ToolUse tu -> main.add(new Item("tool_call", tu.tool(),
                        "TOOL_CALL " + tu.tool() + " " + (tu.input() == null ? "{}" : tu.input())));
                    case ToolResult tr when RECALL.equals(toolNames.get(tr.toolUseId())) ->
                        recalls.add(recallReference(toolUses.get(tr.toolUseId()), tr));
                    case ToolResult tr when ASK_USER.equals(toolNames.get(tr.toolUseId())) -> {
                        answered.add(tr.toolUseId());
                        asks.add(askText(toolUses.get(tr.toolUseId()), tr));
                    }
                    case ToolResult tr when tr.output().length() > maxChars -> large.add(tr);
                    case ToolResult tr -> {
                        var name = toolNames.get(tr.toolUseId());
                        main.add(new Item("tool_result", name,
                            "TOOL_RESULT " + (name == null ? "" : name + " ") + tr.output()));
                    }
                }
            }
        }
        for (var e : toolUses.entrySet()) {
            if (ASK_USER.equals(e.getValue().tool()) && !answered.contains(e.getKey())) {
                asks.add(askText(e.getValue(), null));
            }
        }
        var out = new ArrayList<Chunk>();
        var agent = turn.agent().value();
        for (var text : users) out.add(make(agent, turn.number(), out.size(), timestamp, "user", null, text));
        for (var text : asks) out.add(make(agent, turn.number(), out.size(), timestamp, "user", ASK_USER, text));
        for (var group : groupItems(main)) {
            var kinds = new LinkedHashSet<String>();
            var tools = new LinkedHashSet<String>();
            var text = new StringBuilder();
            for (var item : group) {
                kinds.add(item.kind());
                if (item.tool() != null) tools.add(item.tool());
                line(text, item.text());
            }
            var kind = kinds.size() == 1 ? kinds.iterator().next() : "mixed";
            var tool = tools.size() == 1 ? tools.iterator().next() : null;
            out.add(make(agent, turn.number(), out.size(), timestamp, kind, tool, text.toString().stripTrailing()));
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

    /** Groups the items of the main text. A group has at most {@code maxChars} characters. A tool result is never cut here, because it has at most
     * {@code maxChars} characters already. A longer text item is split. */
    private List<List<Item>> groupItems(List<Item> items) {
        var groups = new ArrayList<List<Item>>();
        var cur = new ArrayList<Item>();
        var size = 0;
        for (var item : items) {
            if (item.text().length() > maxChars && !"tool_result".equals(item.kind())) {
                if (!cur.isEmpty()) groups.add(cur);
                cur = new ArrayList<>();
                size = 0;
                for (var piece : split(item.text())) groups.add(List.of(new Item(item.kind(), item.tool(), piece)));
                continue;
            }
            var len = item.text().length() + 1;
            if (!cur.isEmpty() && size + len > maxChars) {
                groups.add(cur);
                cur = new ArrayList<>();
                size = 0;
            }
            cur.add(item);
            size += len;
        }
        if (!cur.isEmpty()) groups.add(cur);
        return groups;
    }

    /** The most characters of one answer in an {@code ask_user} chunk. */
    static final int MAX_ANSWER_CHARS = 2_000;
    private static final Pattern ANSWER_LINE = Pattern.compile("^- (\\S+) \".*\": (.*)$");

    private static String cut(String text) {
        return text.length() <= MAX_ANSWER_CHARS ? text : text.substring(0, MAX_ANSWER_CHARS) + " [cut]";
    }

    /** The answers in a result text, by question id. Lines that start with {@code - id "prompt": } begin an answer. */
    private static java.util.Map<String, String> answersIn(String output, String from) {
        var out = new java.util.LinkedHashMap<String, String>();
        var start = from == null ? 0 : output.indexOf(from);
        if (start < 0) return out;
        String last = null;
        for (var line : output.substring(start).split("\n", -1)) {
            var m = ANSWER_LINE.matcher(line);
            if (m.matches()) {
                last = m.group(1);
                out.put(last, m.group(2));
            } else if (last != null && !line.startsWith("Answer in chat.")) {
                out.put(last, out.get(last) + "\n" + line);
            }
        }
        return out;
    }

    /** The text of an {@code ask_user} chunk for a call with {@code questions}. */
    private static String formText(JsonNode input, ToolResult result) {
        var sb = new StringBuilder();
        if (input.path("title").isString() && !input.path("title").asString().isBlank()) {
            sb.append("FORM ").append(input.path("title").asString()).append('\n');
        }
        var output = result == null ? "" : result.output();
        var answers = result == null || result.error() ? java.util.Map.<String, String>of()
            : answersIn(output, output.startsWith("User answers:") ? "User answers:"
                : output.contains("Answers so far:") ? "Answers so far:" : null);
        var number = 0;
        for (var q : input.get("questions")) {
            number++;
            var id = q.path("id").isString() && !q.path("id").asString().isBlank() ? q.path("id").asString() : "q" + number;
            sb.append("QUESTION ").append(id).append(' ').append(q.path("prompt").asString()).append('\n');
            if (q.path("options").isArray() && !q.path("options").isEmpty()) {
                var labels = new ArrayList<String>();
                for (var o : q.path("options")) labels.add(o.isObject() ? o.path("label").asString() : o.asString());
                var multi = "multi".equalsIgnoreCase(q.path("kind").asString(""));
                sb.append("CHOICES ").append(multi ? "(multi) " : "").append(String.join(" | ", labels)).append('\n');
            }
            if (answers.containsKey(id)) sb.append("ANSWER ").append(id).append(' ').append(cut(answers.get(id))).append('\n');
        }
        if (result == null) return sb.append("ANSWER (none)").toString();
        if (result.error()) return sb.append("ERROR ").append(output).toString();
        if (output.startsWith("The user declined")) sb.append("DECLINED");
        else if (output.startsWith("The user wants to talk")) {
            var note = Pattern.compile("(?m)^Note from the user: (.*)$").matcher(output);
            sb.append("CHAT").append(note.find() ? " " + cut(note.group(1)) : "");
        }
        return sb.toString().stripTrailing();
    }

    /** The text of an {@code ask_user} chunk: the question, the choices, and the answer. */
    private static String askText(ToolUse use, ToolResult result) {
        var sb = new StringBuilder();
        var input = use == null ? null : use.input();
        if (input != null && input.path("questions").isArray() && !input.path("questions").isEmpty()) {
            return formText(input, result);
        }
        if (result != null && !result.error() && result.output().startsWith("The user declined")) {
            sb.append("QUESTION ").append(input != null && input.has("question") ? input.get("question").asString() : "");
            return sb.append("\nDECLINED").toString();
        }
        if (result != null && !result.error() && result.output().startsWith("The user wants to talk")) {
            sb.append("QUESTION ").append(input != null && input.has("question") ? input.get("question").asString() : "");
            var note = Pattern.compile("(?m)^Note from the user: (.*)$").matcher(result.output());
            return sb.append("\nCHAT").append(note.find() ? " " + cut(note.group(1)) : "").toString();
        }
        sb.append("QUESTION ").append(input != null && input.has("question") ? input.get("question").asString() : "");
        if (input != null && input.has("choices") && input.get("choices").isArray() && !input.get("choices").isEmpty()) {
            var choices = new ArrayList<String>();
            for (var choice : input.get("choices")) choices.add(choice.asString());
            sb.append("\nCHOICES ").append(String.join(" | ", choices));
        }
        if (result == null) return sb.append("\nANSWER (none)").toString();
        var output = result.output();
        if (!result.error() && output.startsWith(ANSWER_PREFIX)) output = output.substring(ANSWER_PREFIX.length());
        return sb.append(result.error() ? "\nERROR " : "\nANSWER ").append(cut(output)).toString();
    }

    private List<Chunk> chunkV1(Turn turn, Instant timestamp) {
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
