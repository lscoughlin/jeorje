package org.github.lscoughlin.jeorje.core.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import tools.jackson.databind.JsonNode;

/**
 * Stops an agent from reading the same thing again and again. A read-only call that repeats an earlier call gets
 * a short note and does not run, when nothing has changed: the file has the same size and time, and no tool that
 * changes the workspace ran since. The result of the first call is in the context or in the history, and the note
 * says how to read it again with {@code recall}.
 *
 * <p>A call with another range, another pattern, or a changed file runs as usual.
 */
final class RepeatGuard {
    /** The tools that the guard watches. */
    static final Set<String> TOOLS = Set.of("read", "ls", "glob", "grep");
    /** The repeat in one turn at which the note tells the model to stop reading. */
    static final int STOP_AT = 3;

    private record Seen(int turn, long size, long modified) {}

    private final Path workingDirectory;
    private final Map<String, Seen> seen = new HashMap<>();
    private final Map<Integer, Integer> repeats = new HashMap<>();

    RepeatGuard(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /** Returns the note for a repeated call, or null when the call must run. */
    synchronized ToolResultData check(ToolUse use, int turn) {
        if (!TOOLS.contains(use.tool())) return null;
        var earlier = seen.get(key(use));
        if (earlier == null) return null;
        var state = state(use);
        if (state == null || state[0] != earlier.size() || state[1] != earlier.modified()) return null;
        var count = repeats.merge(turn, 1, Integer::sum);
        var text = "Already done in turn " + earlier.turn() + ": " + use.tool() + " " + shorten(use.input())
            + ". The result is in your context or in the history. Use recall with the query \"" + query(use)
            + "\" to read it again, or change the range.";
        if (count >= STOP_AT) text += " Stop reading. Use what you have, or write the plan now.";
        return ToolResultData.ok(text);
    }

    /** Remembers a call that gave a result. A call that failed, or that repeats, is not remembered. */
    synchronized void record(ToolUse use, ToolResultData result, int turn) {
        if (!TOOLS.contains(use.tool()) || result.error()) return;
        var state = state(use);
        if (state == null) return;
        seen.putIfAbsent(key(use), new Seen(turn, state[0], state[1]));
    }

    /** Forgets all calls. A tool that changes the workspace ran, so an old result can be wrong. */
    synchronized void invalidate() {
        seen.clear();
    }

    /** Forgets everything, also the counts. */
    synchronized void reset() {
        seen.clear();
        repeats.clear();
    }

    /** The size and the time of the file of a {@code read}, zeros for another tool, null if the file is missing. */
    private long[] state(ToolUse use) {
        if (!use.tool().equals("read")) return new long[] {0, 0};
        var input = use.input();
        if (input == null || !input.path("path").isString()) return null;
        var file = workingDirectory.resolve(input.path("path").asString()).normalize();
        try {
            if (!Files.isRegularFile(file)) return null;
            return new long[] {Files.size(file), Files.getLastModifiedTime(file).toMillis()};
        } catch (IOException e) {
            return null;
        }
    }

    private static String key(ToolUse use) {
        var sb = new StringBuilder(use.tool()).append(' ');
        canonical(use.input(), sb);
        return sb.toString();
    }

    private static void canonical(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull()) {
            sb.append("null");
        } else if (node.isObject()) {
            sb.append('{');
            var names = new TreeSet<String>();
            node.propertyNames().forEach(names::add);
            for (var name : names) {
                sb.append(name).append(':');
                canonical(node.get(name), sb);
                sb.append(',');
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            for (var item : node) {
                canonical(item, sb);
                sb.append(',');
            }
            sb.append(']');
        } else {
            sb.append(node.toString());
        }
    }

    private static String query(ToolUse use) {
        var input = use.input();
        if (input == null) return use.tool();
        var patternFirst = use.tool().equals("grep") || use.tool().equals("glob");
        for (var field : patternFirst ? new String[] {"pattern", "path"} : new String[] {"path", "pattern"}) {
            if (input.path(field).isString() && !input.path(field).asString().isBlank()) {
                return input.path(field).asString();
            }
        }
        return use.tool();
    }

    private static String shorten(JsonNode input) {
        var text = input == null ? "{}" : input.toString();
        return text.length() > 120 ? text.substring(0, 119) + "…" : text;
    }
}
