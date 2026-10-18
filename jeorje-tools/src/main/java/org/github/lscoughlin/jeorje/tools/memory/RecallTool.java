package org.github.lscoughlin.jeorje.tools.memory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.Filters;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;
import org.github.lscoughlin.jeorje.core.port.Syntax;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code recall} tool. It has two modes. With {@code query} it runs a hybrid search over the
 * full index of the session: the chunks of all agents. With {@code chunkId} it returns the full
 * text of a chunk or a window of chunks around it.
 *
 * <p>The first line of a search result is the reference line. It has the query, the filters, and the
 * ids of the hits. The history stores only this line (see {@link #historyText}), never the text.
 */
public final class RecallTool extends RecordTool<RecallTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "recall";
    /** The prefix of the reference line. */
    public static final String REFERENCE_PREFIX = "recall-ref: ";

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 50;
    static final int MAX_WINDOW = 10;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The filters of a search. */
    public record SearchFilters(
            @Describe("Only chunks of this agent id.") String agentId,
            @Describe("First turn, inclusive.") Integer fromTurn,
            @Describe("Last turn, inclusive.") Integer toTurn,
            @Describe("Only chunks of this kind, for example tool_result.") String kind,
            @Describe("Only chunks of this tool.") String toolName) {}

    /** The input of {@code recall}. */
    public record Input(
            @Describe("Search text. Use the terms of a recall line. Use this or chunkId.") String query,
            @Describe("Narrow the search.") SearchFilters filters,
            @Describe("Most hits to return. Default 10, most 50.") Integer limit,
            @Describe("Id of a chunk to read in full. Use this or query.") String chunkId,
            @Describe("With chunkId: number of chunks before it to include. Default 0.") Integer before,
            @Describe("With chunkId: number of chunks after it to include. Default 0.") Integer after) {}

    private final String sessionId;
    private final Function<String, String> agentNames;

    /**
     * @param sessionId the id of the current session. Chunks of another session never appear.
     * @param agentNames gives the agent name for an agent id
     */
    public RecallTool(String sessionId, Function<String, String> agentNames) {
        super(NAME, "Search the history of this session, or read one stored chunk. The search covers "
                + "the chunks of all agents. Use it when a pruned chunk hides detail that you need, "
                + "instead of a repeat tool run. Give query to search. Give chunkId to read a chunk.",
                Input.class, true);
        this.sessionId = sessionId;
        this.agentNames = agentNames == null ? id -> id : agentNames;
    }

    /** The text that the history stores for a recall result: the reference line only. */
    public static String historyText(String output) {
        var end = output.indexOf('\n');
        var first = end < 0 ? output : output.substring(0, end);
        return first.startsWith(REFERENCE_PREFIX) ? first : output;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) {
        var store = context.history();
        if (store == null) return ToolResultData.error("The history is not available.");
        var hasQuery = input.query() != null && !input.query().isBlank();
        var hasChunk = input.chunkId() != null && !input.chunkId().isBlank();
        if (hasQuery == hasChunk) return ToolResultData.error("Give either query or chunkId, not both and not none.");
        return hasChunk ? read(input, context) : search(input, context);
    }

    private boolean inSession(Chunk chunk) {
        return sessionId == null || sessionId.equals(chunk.sessionId());
    }

    private ToolResultData read(Input input, ToolContext context) {
        var before = clamp(input.before(), 0, MAX_WINDOW);
        var after = clamp(input.after(), 0, MAX_WINDOW);
        var store = context.history();
        List<Chunk> chunks;
        if (before == 0 && after == 0) {
            chunks = store.get(input.chunkId()).stream().toList();
        } else {
            chunks = store.window(input.chunkId(), before, after);
        }
        chunks = chunks.stream().filter(this::inSession).toList();
        if (chunks.isEmpty()) return ToolResultData.error("No chunk with id " + input.chunkId() + " in this session.");
        var out = new StringBuilder();
        for (var chunk : chunks) {
            out.append("--- ").append(chunk.chunkId()).append(" | agent ").append(describeAgent(chunk))
                    .append(" | turn ").append(chunk.turn()).append(" | ").append(chunk.kind());
            if (chunk.toolName() != null) out.append(" | ").append(chunk.toolName());
            out.append(" ---\n").append(chunk.text()).append('\n');
        }
        return ToolResultData.ok(out.toString());
    }

    private ToolResultData search(Input input, ToolContext context) {
        var limit = clamp(input.limit(), 1, MAX_LIMIT, DEFAULT_LIMIT);
        var filters = toFilters(input.filters());
        var includeRecall = filters.toolNames().contains(NAME);
        var hits = context.history().search(new HistoryQuery(input.query(), Syntax.HYBRID, filters, limit * 3));
        var ids = new ArrayList<String>();
        var body = new StringBuilder();
        var seen = new HashSet<String>();
        for (var hit : hits) {
            var chunk = hit.chunk();
            if (ids.size() >= limit) break;
            if (!inSession(chunk)) continue;
            if (!includeRecall && NAME.equals(chunk.toolName())) continue;
            if (!seen.add(chunk.chunkId())) continue;
            ids.add(chunk.chunkId());
            body.append(chunk.chunkId()).append(" | agent ").append(describeAgent(chunk))
                    .append(" | turn ").append(chunk.turn()).append(" | score ")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", hit.score())).append(" | ")
                    .append(chunk.kind());
            if (chunk.toolName() != null) body.append(" | ").append(chunk.toolName());
            body.append('\n').append("  ").append(oneLine(hit.snippet())).append('\n');
        }
        var out = new StringBuilder(reference(input, ids)).append('\n');
        if (ids.isEmpty()) out.append("No chunks match.\n");
        else out.append(body).append("Use recall with chunkId to read a chunk in full.\n");
        return ToolResultData.ok(out.toString());
    }

    private String reference(Input input, List<String> ids) {
        var node = JSON.createObjectNode();
        node.put("query", input.query());
        node.set("filters", JSON.valueToTree(input.filters()));
        var array = node.putArray("ids");
        ids.forEach(array::add);
        return REFERENCE_PREFIX + node;
    }

    private String describeAgent(Chunk chunk) {
        var name = agentNames.apply(chunk.agentId());
        return name == null || name.equals(chunk.agentId()) ? chunk.agentId() : name + " (" + chunk.agentId() + ")";
    }

    private static Filters toFilters(SearchFilters f) {
        if (f == null) return Filters.none();
        return new Filters(
                blank(f.agentId()) ? Set.of() : Set.of(f.agentId()),
                f.fromTurn() == null ? OptionalInt.empty() : OptionalInt.of(f.fromTurn()),
                f.toTurn() == null ? OptionalInt.empty() : OptionalInt.of(f.toTurn()),
                Optional.empty(), Optional.empty(),
                blank(f.kind()) ? Set.of() : Set.of(f.kind()),
                blank(f.toolName()) ? Set.of() : Set.of(f.toolName()));
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String oneLine(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").strip(); }

    private static int clamp(Integer value, int min, int max) { return clamp(value, min, max, min); }

    private static int clamp(Integer value, int min, int max, int fallback) {
        return value == null ? fallback : Math.max(min, Math.min(max, value));
    }
}
