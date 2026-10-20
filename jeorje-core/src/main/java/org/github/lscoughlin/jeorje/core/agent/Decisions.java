package org.github.lscoughlin.jeorje.core.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.model.Turn;

/**
 * The decisions of the user, as a short block that the agent pins in every request. The block has the answers
 * of the user to {@code ask_user}. It is built again for each request from the finished turns, so that a
 * decision stays visible when its turn is pruned or leaves the request. The other texts of the user are not in
 * the block: compaction never changes them, and {@code recall} with the filter kind user finds them.
 */
public final class Decisions {
    /** The title line of the block. */
    public static final String TITLE = "Decisions of the user:";
    /** The most characters of the block, without the title. */
    public static final int MAX_CHARS = 4_000;
    static final String OLDER = "(older decisions: use recall with the filter kind user)";

    private static final Pattern ANSWER_LINE = Pattern.compile("^- (\\S+) \"(.*)\": (.*)$");
    private static final String ANSWER = "User answer: ";
    private static final String ANSWERS = "User answers:";
    private static final String CHAT = "The user wants to talk about this";
    private static final Pattern NOTE = Pattern.compile("(?m)^Note from the user: (.*)$");

    private Decisions() {}

    /**
     * Builds the block.
     *
     * @param turns the finished turns of the agent, oldest first. The turn that runs is not in the block,
     *              because the request always holds it.
     * @return the text of the block, or null when there is no decision
     */
    public static String build(List<Turn> turns) {
        var lines = new ArrayList<String>();
        for (var turn : turns) collect(turn.messages(), lines);
        if (lines.isEmpty()) return null;
        var kept = new ArrayList<String>();
        var size = 0;
        var cut = false;
        for (var i = lines.size() - 1; i >= 0; i--) {
            var len = lines.get(i).length() + 1;
            if (size + len > MAX_CHARS && !kept.isEmpty()) {
                cut = true;
                break;
            }
            kept.add(lines.get(i));
            size += len;
        }
        java.util.Collections.reverse(kept);
        var sb = new StringBuilder(TITLE);
        if (cut) sb.append('\n').append(OLDER);
        for (var line : kept) sb.append('\n').append(line);
        return sb.toString();
    }

    private static void collect(List<Message> messages, List<String> lines) {
        var uses = new HashMap<String, ToolUse>();
        for (var m : messages) {
            for (var c : m.content()) {
                switch (c) {
                    case ToolUse u when "ask_user".equals(u.tool()) -> uses.put(u.id(), u);
                    case ToolResult r when uses.containsKey(r.toolUseId()) -> {
                        if (!r.error()) answer(uses.get(r.toolUseId()), r.output(), lines);
                    }
                    default -> { }
                }
            }
        }
    }

    private static void answer(ToolUse use, String output, List<String> lines) {
        if (output.startsWith(ANSWER)) {
            lines.add("- " + prompt(use) + ": " + oneLine(output.substring(ANSWER.length())));
        } else if (output.startsWith(ANSWERS)) {
            for (var m : answerLines(output, ANSWERS)) lines.add("- " + m[0] + ": " + oneLine(m[1]));
        } else if (output.startsWith(CHAT)) {
            var note = NOTE.matcher(output);
            lines.add("- " + prompt(use) + ": (the user wanted to chat" + (note.find() ? ": " + oneLine(note.group(1)) : "")
                + ")");
            if (output.contains("Answers so far:")) {
                for (var m : answerLines(output, "Answers so far:")) lines.add("- " + m[0] + ": " + oneLine(m[1]));
            }
        }
    }

    /** The prompt and the answer of each line after a marker. A line that continues an answer joins it. */
    private static List<String[]> answerLines(String output, String marker) {
        var out = new ArrayList<String[]>();
        var start = output.indexOf(marker);
        if (start < 0) return out;
        String[] last = null;
        for (var line : output.substring(start + marker.length()).split("\n", -1)) {
            var m = ANSWER_LINE.matcher(line);
            if (m.matches()) {
                last = new String[] {m.group(2), m.group(3)};
                out.add(last);
            } else if (last != null && !line.startsWith("Answer in chat.") && !line.isBlank()) {
                last[1] = last[1] + " " + line.strip();
            }
        }
        return out;
    }

    private static String prompt(ToolUse use) {
        var input = use.input();
        if (input == null) return "question";
        if (input.path("question").isString()) return input.path("question").asString().strip();
        if (input.path("questions").isArray() && !input.path("questions").isEmpty()) {
            return input.path("questions").get(0).path("prompt").asString("question").strip();
        }
        return "question";
    }

    private static String oneLine(String text) {
        var line = text.strip().replaceAll("\\s+", " ");
        return line.length() > 600 ? line.substring(0, 599) + "…" : line;
    }
}
