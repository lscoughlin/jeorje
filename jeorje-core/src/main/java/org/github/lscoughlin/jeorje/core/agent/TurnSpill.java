package org.github.lscoughlin.jeorje.core.agent;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.model.Content;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.model.Turn;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.session.TurnChunker;

/**
 * Keeps the turn that runs inside the budget. A turn can read many files, and compaction runs only when a turn
 * ends. When the request grows too big, {@link #spill} moves the biggest old tool results out of the messages of
 * the turn. The full text goes to the context store as chunks. The message keeps a stub with the first lines and a
 * recall hint. The transcript of the session keeps the full text: {@link #full} puts it back.
 *
 * <p>The ids of the chunks of a spill start at part {@value #FIRST_PART}, so they never clash with the parts that
 * {@link TurnChunker} writes at the end of the turn.
 */
final class TurnSpill {
    /** The first part number of a spill chunk. */
    static final int FIRST_PART = 1_000;
    /** The tool results at the end of the turn that stay whole. */
    static final int LIVE_TAIL = 6;
    /** The least number of tool results in the turn before a spill starts. */
    static final int MIN_RESULTS = 8;
    /** A result with fewer characters is not worth a stub. */
    static final int MIN_CHARS = 800;
    /** The start of every stub. */
    static final String MARK = "[spilled: ";
    private static final int HEAD_LINES = 5;
    private static final int MAX_KEYWORDS = 6;
    private static final Set<String> KEEP_WHOLE = Set.of("todo", "todowrite", "todo_write", "todos", "skill",
        "ask_user", "plan_submit", "recall");
    private static final Pattern PATH = Pattern.compile("(?:[\\w.~-]+/)+[\\w.-]+");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{4,}");

    /** What one spill did. */
    record Outcome(int spilled, int freedChars) {}

    private final ContextStore store;
    private final String sessionId;
    private final AgentId agent;
    private final int turn;
    private final Clock clock;
    private final Map<String, String> originals = new LinkedHashMap<>();
    private int nextPart = FIRST_PART;

    TurnSpill(ContextStore store, String sessionId, AgentId agent, int turn, Clock clock) {
        this.store = store;
        this.sessionId = sessionId;
        this.agent = agent;
        this.turn = turn;
        this.clock = clock;
    }

    /** True when this turn has spilled at least one result. */
    boolean active() { return !originals.isEmpty(); }

    /**
     * Spills tool results until about {@code charsToFree} characters are gone, or no candidate is left. Changes
     * the list in place.
     */
    Outcome spill(List<Message> messages, TurnChunker chunker, long charsToFree) {
        var tools = new HashMap<String, ToolUse>();
        var results = new ArrayList<Candidate>();
        for (var i = 0; i < messages.size(); i++) {
            for (var c : messages.get(i).content()) {
                if (c instanceof ToolUse u) tools.put(u.id(), u);
                if (c instanceof ToolResult r) results.add(new Candidate(i, r, results.size()));
            }
        }
        if (results.size() < MIN_RESULTS) return new Outcome(0, 0);
        var live = results.subList(Math.max(0, results.size() - LIVE_TAIL), results.size());
        var candidates = new ArrayList<Candidate>();
        for (var c : results) {
            var use = tools.get(c.result().toolUseId());
            if (live.contains(c) || c.result().error() || c.result().output().length() < MIN_CHARS) continue;
            if (c.result().output().startsWith(MARK)) continue;
            if (use == null || KEEP_WHOLE.contains(use.tool())) continue;
            candidates.add(c);
        }
        candidates.sort(Comparator.comparingInt((Candidate c) -> -c.result().output().length())
            .thenComparingInt(Candidate::order));
        var freed = 0L;
        var count = 0;
        var replaced = new HashMap<String, ToolResult>();
        for (var c : candidates) {
            if (freed >= charsToFree) break;
            var stub = stubFor(c.result(), tools.get(c.result().toolUseId()), chunker);
            originals.put(c.result().toolUseId(), c.result().output());
            replaced.put(c.result().toolUseId(), new ToolResult(c.result().toolUseId(), stub, false));
            freed += c.result().output().length() - stub.length();
            count++;
        }
        if (count == 0) return new Outcome(0, 0);
        for (var i = 0; i < messages.size(); i++) {
            var content = messages.get(i).content();
            if (content.stream().noneMatch(c -> c instanceof ToolResult r && replaced.containsKey(r.toolUseId()))) continue;
            var next = new ArrayList<Content>();
            for (var c : content) {
                next.add(c instanceof ToolResult r && replaced.containsKey(r.toolUseId()) ? replaced.get(r.toolUseId()) : c);
            }
            messages.set(i, new Message(messages.get(i).role(), next));
        }
        return new Outcome(count, (int) Math.min(Integer.MAX_VALUE, freed));
    }

    private record Candidate(int message, ToolResult result, int order) {}

    private String stubFor(ToolResult result, ToolUse use, TurnChunker chunker) {
        var output = result.output();
        var pieces = chunker.split(output);
        var chunks = new ArrayList<Chunk>();
        var now = clock.instant();
        for (var piece : pieces) {
            var part = nextPart++;
            chunks.add(new Chunk(agent.value() + ":" + turn + "." + part, sessionId, agent.value(), turn, part, now,
                "tool_result", use.tool(), piece));
        }
        store.append(chunks);
        var input = use.input() == null ? "{}" : use.input().toString();
        if (input.length() > 80) input = input.substring(0, 79) + "…";
        var lines = output.split("\n", -1).length;
        var head = String.join("\n", output.lines().limit(HEAD_LINES).toList());
        var more = pieces.size() > 1 ? ", " + pieces.size() + " parts" : "";
        return MARK + use.tool() + " " + input + " | " + lines + " lines, " + output.length() + " chars" + more
            + " | recall: " + String.join(", ", keywords(use, output)) + ", id=" + chunks.getFirst().chunkId() + "]\n"
            + head;
    }

    /** The keywords of a stub: the paths of the call, and then the most frequent identifiers of the result. */
    static List<String> keywords(ToolUse use, String output) {
        var out = new ArrayList<String>();
        var input = use.input() == null ? "" : use.input().toString();
        var paths = PATH.matcher(input);
        while (paths.find() && out.size() < 2) if (!out.contains(paths.group())) out.add(paths.group());
        var counts = new HashMap<String, Integer>();
        var ids = IDENTIFIER.matcher(output.length() > 20_000 ? output.substring(0, 20_000) : output);
        while (ids.find()) counts.merge(ids.group(), 1, Integer::sum);
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey).filter(k -> !out.contains(k)).limit(MAX_KEYWORDS - out.size()).forEach(out::add);
        if (out.isEmpty()) out.add(use.tool());
        return out;
    }

    /** The turn with the full text of every spilled result, for the transcript. */
    Turn full(Turn turn) {
        if (originals.isEmpty()) return turn;
        var messages = new ArrayList<Message>();
        for (var m : turn.messages()) {
            var content = new ArrayList<Content>();
            for (var c : m.content()) {
                content.add(c instanceof ToolResult r && originals.containsKey(r.toolUseId())
                    ? new ToolResult(r.toolUseId(), originals.get(r.toolUseId()), r.error()) : c);
            }
            messages.add(new Message(m.role(), content));
        }
        return new Turn(turn.agent(), turn.number(), messages);
    }
}
