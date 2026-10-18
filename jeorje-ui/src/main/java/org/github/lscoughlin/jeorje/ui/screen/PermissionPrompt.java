package org.github.lscoughlin.jeorje.ui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/** The permission prompt box. It is a small state machine over key names. */
public final class PermissionPrompt {
    private static final List<Decision> ORDER =
            List.of(Decision.ALLOW_ONCE, Decision.ALLOW_SESSION, Decision.ALLOW_PROJECT, Decision.DENY);
    private static final List<String> LABELS =
            List.of("[y] allow once", "[s] allow for the session", "[p] allow in the project", "[n] deny");

    private final AgentId agent;
    private final ToolUse toolUse;
    private int selected;

    public PermissionPrompt(AgentId agent, ToolUse toolUse) {
        this.agent = agent;
        this.toolUse = toolUse;
    }

    public AgentId agent() { return agent; }

    public ToolUse toolUse() { return toolUse; }

    public int selected() { return selected; }

    /**
     * Handles one key. Keys: {@code y s p n}, {@code 1}-{@code 4}, {@code esc} (deny), {@code up}, {@code down},
     * and {@code enter}. Returns the decision when the key ends the prompt.
     */
    public Optional<Decision> handleKey(String key) {
        return switch (key.toLowerCase()) {
            case "y", "1" -> Optional.of(Decision.ALLOW_ONCE);
            case "s", "2" -> Optional.of(Decision.ALLOW_SESSION);
            case "p", "3" -> Optional.of(Decision.ALLOW_PROJECT);
            case "n", "d", "4", "esc" -> Optional.of(Decision.DENY);
            case "up" -> {
                selected = (selected + ORDER.size() - 1) % ORDER.size();
                yield Optional.empty();
            }
            case "down" -> {
                selected = (selected + 1) % ORDER.size();
                yield Optional.empty();
            }
            case "enter" -> Optional.of(ORDER.get(selected));
            default -> Optional.empty();
        };
    }

    /** The box lines, each at most {@code width} columns wide. */
    public List<AttributedString> render(int width) {
        var inner = Math.max(10, width - 4);
        var rows = new ArrayList<String>();
        rows.add("Permission needed: " + agent + " wants to run " + toolUse.tool());
        var input = toolUse.input() == null ? "" : toolUse.input().toString();
        rows.add(input);
        for (var i = 0; i < LABELS.size(); i++) rows.add((i == selected ? "> " : "  ") + LABELS.get(i));
        var out = new ArrayList<AttributedString>();
        var border = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
        out.add(new AttributedString("+" + "-".repeat(inner + 2) + "+", border));
        for (var row : rows) {
            var text = row.length() > inner ? row.substring(0, inner - 1) + "…" : row;
            var sb = new AttributedStringBuilder();
            sb.append("| ", border).append(text + " ".repeat(inner - text.length())).append(" |", border);
            out.add(sb.toAttributedString());
        }
        out.add(new AttributedString("+" + "-".repeat(inner + 2) + "+", border));
        return out;
    }
}
