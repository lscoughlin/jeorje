package org.github.lscoughlin.jeorje.core.compaction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.model.Chunk;

/**
 * Assigns a {@link Protection} class to each chunk. The rules use only {@code kind}, {@code toolName}, and the
 * turn events that the caller gives. The classifier never calls a model.
 *
 * <p>Tool outputs and build logs are tool results. They are {@code AGGRESSIVE}. A search result that a later
 * edit, write, or answer used is a concluded search. {@link #concludedSearches} finds these results, so that
 * compaction prunes them earlier. The caller runs the classifier at each compaction, on all chunks of the agent.
 */
public final class ContextClassifier {
    private static final Pattern FAILURE = Pattern.compile(
        "(?im)(BUILD FAILURE|\\[ERROR\\]|^\\s*ERROR[\\s:]|\\bFAILED\\b|exit code[: ]+[1-9]|Exception in thread"
            + "|^\\S*Exception:|Tests run:.*(Failures: [1-9]|Errors: [1-9]))");
    private static final Set<String> TODO_TOOLS = Set.of("todo", "todowrite", "todo_write", "todos");
    private static final Set<String> FILE_TOOLS = Set.of("read", "write", "edit", "patch", "apply_patch");
    private static final Set<String> SEARCH_TOOLS = Set.of("grep", "glob", "ls", "find", "search");
    private static final Set<String> EDIT_TOOLS = Set.of("write", "edit", "patch", "apply_patch", "multiedit");
    private static final Pattern MARKER = Pattern.compile("^(USER: |ASSISTANT: |TOOL_CALL |TOOL_RESULT )");
    private static final Pattern IDENTIFIER = Pattern.compile(
        "\\b[A-Za-z_][A-Za-z0-9_]*(?:[a-z][A-Z]|_)[A-Za-z0-9_]*\\b");
    private static final int MIN_TERM = 4;
    private static final int MAX_TERMS = 60;
    private static final int LEGACY_HEAD = 300;

    /** One part of a chunk text: the type is USER, ASSISTANT, TOOL_CALL, TOOL_RESULT, or OTHER. */
    private record Segment(String type, String tool, String text) {}

    /**
     * The facts about the session that the rules need.
     *
     * @param currentTurn the number of the current turn
     * @param recentTurns the number of recent turns (default 6)
     * @param pinnedChunkIds chunks that the user pinned
     * @param resolvedChunkIds failing chunks that a user or agent note marked as resolved
     * @param skillLastUsedTurn the turn of the last use of each skill chunk (default: the turn of the chunk)
     * @param maxProtectedTokens the limit for protected skill content
     */
    public record Context(int currentTurn, int recentTurns, Set<String> pinnedChunkIds, Set<String> resolvedChunkIds,
                          Map<String, Integer> skillLastUsedTurn, int maxProtectedTokens) {
        public Context {
            pinnedChunkIds = Set.copyOf(pinnedChunkIds);
            resolvedChunkIds = Set.copyOf(resolvedChunkIds);
            skillLastUsedTurn = Map.copyOf(skillLastUsedTurn);
        }

        public static Context of(int currentTurn) {
            return new Context(currentTurn, 6, Set.of(), Set.of(), Map.of(), 20_000);
        }
    }

    /** Classifies all chunks. The chunks must be in history order. */
    public Map<String, Protection> classify(List<Chunk> chunks, Context ctx) {
        var out = new HashMap<String, Protection>();
        var firstUser = new HashMap<String, Chunk>();
        for (var c : chunks) {
            if (c.text().startsWith("USER:")) firstUser.putIfAbsent(c.agentId(), c);
        }
        for (var c : chunks) out.put(c.chunkId(), base(c, ctx, firstUser.get(c.agentId()) == c));
        applyErrorState(chunks, ctx, out);
        applySkillLimit(chunks, ctx, out);
        return out;
    }

    /** Classifies one chunk by the static rules only. */
    public Protection classifyOne(Chunk c, Context ctx) {
        return base(c, ctx, false);
    }

    private Protection base(Chunk c, Context ctx, boolean original) {
        if (ctx.pinnedChunkIds().contains(c.chunkId())) return Protection.PROTECTED;
        var tool = c.toolName();
        if (tool != null && TODO_TOOLS.contains(tool.toLowerCase())) return Protection.PROTECTED;
        if ("recall".equals(tool)) return Protection.AGGRESSIVE;
        if ("skill".equals(tool) && "tool_result".equals(c.kind())) return Protection.PROTECTED;
        return switch (c.kind()) {
            case "user" -> Protection.PROTECTED;
            case "mixed" -> original ? Protection.PROTECTED : Protection.CAREFUL;
            case "assistant", "subagent_summary" -> Protection.CAREFUL;
            case "tool_result", "tool_call" -> Protection.AGGRESSIVE;
            default -> c.text().startsWith("ASSISTANT:") ? Protection.CAREFUL : Protection.AGGRESSIVE;
        };
    }

    /** The latest failing result of each tool stays protected until a later success or a resolved note. */
    private void applyErrorState(List<Chunk> chunks, Context ctx, Map<String, Protection> out) {
        var latestFailure = new HashMap<String, Chunk>();
        for (var c : chunks) {
            if (!"tool_result".equals(c.kind()) && !"mixed".equals(c.kind())) continue;
            for (var seg : segments(c)) {
                if (!"TOOL_RESULT".equals(seg.type()) || seg.tool() == null
                    || "skill".equals(seg.tool()) || "recall".equals(seg.tool())) continue;
                if (FAILURE.matcher(seg.text()).find()) latestFailure.put(seg.tool(), c);
                else latestFailure.remove(seg.tool());
            }
        }
        for (var f : latestFailure.values()) {
            if (!ctx.resolvedChunkIds().contains(f.chunkId())) out.put(f.chunkId(), Protection.PROTECTED);
        }
    }

    /** Returns true when the text of a tool result, or a tool result inside a turn chunk, shows a failure. */
    public static boolean isFailure(Chunk c) {
        if (!"tool_result".equals(c.kind()) && !"mixed".equals(c.kind())) return false;
        return segments(c).stream().anyMatch(seg -> "TOOL_RESULT".equals(seg.type())
            && FAILURE.matcher(seg.text()).find());
    }

    /** Splits the text of a chunk at its marker lines. */
    private static List<Segment> segments(Chunk c) {
        if ("tool_result".equals(c.kind()) && c.toolName() != null) {
            return List.of(new Segment("TOOL_RESULT", c.toolName(), c.text()));
        }
        var out = new ArrayList<Segment>();
        var type = switch (c.kind()) {
            case "assistant" -> "ASSISTANT";
            case "tool_result" -> "TOOL_RESULT";
            case "tool_call" -> "TOOL_CALL";
            case "user" -> "USER";
            default -> "OTHER";
        };
        var tool = c.toolName();
        var body = new StringBuilder();
        for (var line : c.text().split("\n", -1)) {
            var m = MARKER.matcher(line);
            if (m.find()) {
                flush(out, type, tool, body);
                type = m.group(1).strip().replace(":", "");
                var rest = line.substring(m.end());
                tool = null;
                if (type.startsWith("TOOL_")) {
                    var sp = rest.indexOf(' ');
                    var first = sp < 0 ? rest : rest.substring(0, sp);
                    if (first.matches("[\\w.-]+")) {
                        tool = first;
                    }
                }
                body.append(line).append('\n');
            } else {
                body.append(line).append('\n');
            }
        }
        flush(out, type, tool, body);
        return out;
    }

    private static void flush(List<Segment> out, String type, String tool, StringBuilder body) {
        if (!body.isEmpty()) out.add(new Segment(type, tool, body.toString()));
        body.setLength(0);
    }

    private void applySkillLimit(List<Chunk> chunks, Context ctx, Map<String, Protection> out) {
        var skills = new ArrayList<>(chunks.stream().filter(c -> "skill".equals(c.toolName())
            && out.get(c.chunkId()) == Protection.PROTECTED && !ctx.pinnedChunkIds().contains(c.chunkId())).toList());
        skills.sort(Comparator.comparingInt((Chunk c) -> ctx.skillLastUsedTurn().getOrDefault(c.chunkId(), c.turn()))
            .thenComparing(Chunk::chunkId));
        var total = skills.stream().mapToInt(c -> Tokens.estimate(c.text())).sum();
        for (var c : skills) {
            if (total <= ctx.maxProtectedTokens()) break;
            out.put(c.chunkId(), Protection.AGGRESSIVE);
            total -= Tokens.estimate(c.text());
        }
    }

    /**
     * The paths of files that the agent read, wrote, or edited in the last N turns, and the paths in the
     * protected error chunks. Pruning keeps these paths at every level.
     */
    public Set<String> immediatePaths(List<Chunk> chunks, Context ctx, Map<String, Protection> classes) {
        var paths = new LinkedHashSet<String>();
        for (var c : chunks) {
            var recent = c.turn() > ctx.currentTurn() - ctx.recentTurns();
            if (recent) {
                var calls = false;
                for (var seg : segments(c)) {
                    if ("TOOL_CALL".equals(seg.type()) && seg.tool() != null && FILE_TOOLS.contains(seg.tool())) {
                        calls = true;
                        addPaths(paths, seg.text());
                    }
                }
                var fileTool = c.toolName() != null && FILE_TOOLS.contains(c.toolName());
                if (!calls && fileTool && !"mixed".equals(c.kind())) {
                    addPaths(paths, c.text().substring(0, Math.min(c.text().length(), LEGACY_HEAD)));
                }
            }
            if (classes.get(c.chunkId()) == Protection.PROTECTED) {
                for (var seg : segments(c)) {
                    if ("TOOL_RESULT".equals(seg.type()) && FAILURE.matcher(seg.text()).find()) addPaths(paths, seg.text());
                }
            }
        }
        return paths;
    }

    private static void addPaths(Set<String> paths, String text) {
        var m = Tokens.PATH.matcher(text);
        while (m.find()) paths.add(m.group());
    }

    /**
     * The ids of search results that a later edit, write, or answer used. A search result is a {@code tool_result}
     * chunk of {@code grep}, {@code glob}, {@code ls}, {@code find}, or {@code search}. A later conclusion is an
     * edit or write call, or assistant text, in a later turn or in another chunk of the same turn. It uses the
     * result when it contains a path, a file name, or an identifier of the result. Compaction prunes these
     * results earlier. Pinned and protected chunks are not in the set.
     */
    public Set<String> concludedSearches(List<Chunk> chunks, Context ctx, Map<String, Protection> classes) {
        var out = new LinkedHashSet<String>();
        for (var s : chunks) {
            if (!"tool_result".equals(s.kind()) || s.toolName() == null || !SEARCH_TOOLS.contains(s.toolName())) continue;
            if (classes.getOrDefault(s.chunkId(), Protection.AGGRESSIVE) != Protection.AGGRESSIVE) continue;
            var terms = terms(s.text());
            if (terms.isEmpty()) continue;
            for (var c : chunks) {
                if (!c.agentId().equals(s.agentId()) || c == s) continue;
                if (c.turn() < s.turn() || c.turn() == s.turn() && c.part() == s.part()) continue;
                if (usedBy(c, terms)) {
                    out.add(s.chunkId());
                    break;
                }
            }
        }
        return out;
    }

    private static boolean usedBy(Chunk c, Set<String> terms) {
        for (var seg : segments(c)) {
            var conclusion = switch (seg.type()) {
                case "ASSISTANT" -> true;
                case "TOOL_CALL" -> seg.tool() != null && EDIT_TOOLS.contains(seg.tool());
                default -> false;
            };
            if (!conclusion) continue;
            for (var t : terms) if (seg.text().contains(t)) return true;
        }
        return false;
    }

    private static Set<String> terms(String text) {
        var terms = new LinkedHashSet<String>();
        var m = Tokens.PATH.matcher(text);
        while (m.find() && terms.size() < MAX_TERMS) {
            var p = m.group();
            terms.add(p);
            var name = p.substring(p.lastIndexOf('/') + 1);
            if (name.length() >= MIN_TERM) terms.add(name);
        }
        var id = IDENTIFIER.matcher(text);
        while (id.find() && terms.size() < MAX_TERMS) {
            if (id.group().length() >= MIN_TERM) terms.add(id.group());
        }
        terms.removeIf(t -> t.length() < MIN_TERM);
        return terms;
    }
}
