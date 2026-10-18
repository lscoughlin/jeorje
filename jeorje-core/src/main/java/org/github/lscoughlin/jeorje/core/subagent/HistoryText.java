package org.github.lscoughlin.jeorje.core.subagent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.model.Turn;

/** Facts about the history of a subagent, for the completion summary. */
final class HistoryText {
    private static final Set<String> FILE_TOOLS = Set.of("write", "edit", "patch", "apply_patch");

    private final String full;
    private final String lower;
    private final List<String> files;
    private final String lastAssistant;

    private HistoryText(String full, List<String> files, String lastAssistant) {
        this.full = full;
        this.lower = full.toLowerCase(Locale.ROOT);
        this.files = files;
        this.lastAssistant = lastAssistant;
    }

    static HistoryText of(List<Turn> turns) {
        var sb = new StringBuilder();
        var files = new LinkedHashSet<String>();
        var last = "";
        for (var turn : turns) {
            for (var message : turn.messages()) {
                for (var content : message.content()) {
                    switch (content) {
                        case Text t -> {
                            sb.append(message.role() == Role.USER ? "USER: " : "ASSISTANT: ").append(t.text()).append('\n');
                            if (message.role() == Role.ASSISTANT && !t.text().isBlank()) last = t.text().strip();
                        }
                        case ToolUse use -> {
                            var input = use.input() == null ? "{}" : use.input().toString();
                            sb.append("TOOL_CALL ").append(use.tool()).append(' ').append(input).append('\n');
                            if (FILE_TOOLS.contains(use.tool()) && use.input() != null) {
                                for (var field : List.of("path", "file_path", "file")) {
                                    var node = use.input().path(field);
                                    if (node.isString() && !node.asString().isBlank()) files.add(node.asString());
                                }
                            }
                        }
                        case ToolResult result -> sb.append("TOOL_RESULT ").append(result.output()).append('\n');
                        default -> { }
                    }
                }
            }
        }
        return new HistoryText(sb.toString(), List.copyOf(files), last);
    }

    String full() { return full; }

    boolean contains(String term) { return lower.contains(term.toLowerCase(Locale.ROOT)); }

    List<String> changedFiles() { return files; }

    String lastAssistantText() { return lastAssistant; }

    /** The history for the prompt. It keeps the start and the end when the text is longer than the limit. */
    String capped(int maxChars) {
        if (full.length() <= maxChars) return full;
        var head = Math.min(2_000, maxChars / 5);
        var tail = maxChars - head;
        return full.substring(0, head) + "\n[... middle of the history left out ...]\n"
            + full.substring(full.length() - tail);
    }
}
