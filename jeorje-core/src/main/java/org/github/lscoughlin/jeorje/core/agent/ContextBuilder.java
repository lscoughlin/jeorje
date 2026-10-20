package org.github.lscoughlin.jeorje.core.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.github.lscoughlin.jeorje.core.compaction.ContextEntry;
import org.github.lscoughlin.jeorje.core.model.Content;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.model.Turn;

/**
 * Builds the model request: the system prompt, the pinned items, the pruned chunks, the recent turns,
 * and the messages of the current turn. The estimate is the character count divided by four, scaled
 * with the {@code Usage} values of the provider. The budget is a share of the window (default 60%),
 * and it always leaves room for the output.
 */
public final class ContextBuilder {
    private static final double MIN_SCALE = 0.25;
    private static final double MAX_SCALE = 4.0;

    /** The parts of one request. */
    public record Input(String system, List<String> pinned, List<ContextEntry> older, List<Turn> recentTurns,
                        List<Message> current, List<ToolDefinition> tools) {
        public Input {
            pinned = List.copyOf(pinned);
            older = List.copyOf(older);
            recentTurns = List.copyOf(recentTurns);
            current = List.copyOf(current);
            tools = List.copyOf(tools);
        }
    }

    /**
     * A built request.
     *
     * @param rawEstimate the estimate before the scale
     * @param estimate the estimate with the scale
     * @param droppedTurns recent turns that left the request to meet the budget
     * @param overBudget true if the request is still over the budget
     * @param droppedTurnNumbers the numbers of the dropped turns, oldest first
     */
    public record Built(LlmRequest request, int rawEstimate, int estimate, int budget, int droppedTurns,
                        boolean overBudget, List<Integer> droppedTurnNumbers) {
        public Built {
            droppedTurnNumbers = List.copyOf(droppedTurnNumbers);
        }
    }

    private final int contextWindow;
    private final int budgetPercent;
    private final int maxOutputTokens;
    private double scale = 1.0;

    public ContextBuilder(int contextWindow, int budgetPercent, int maxOutputTokens) {
        this.contextWindow = contextWindow;
        this.budgetPercent = budgetPercent <= 0 ? 60 : budgetPercent;
        this.maxOutputTokens = maxOutputTokens;
    }

    /** The working-context budget: the share of the window, less than the room for the output. */
    public int budget() {
        var share = (int) ((long) contextWindow * budgetPercent / 100);
        return Math.max(1, Math.min(share, contextWindow - maxOutputTokens));
    }

    public synchronized double scale() { return scale; }

    /** Refines the estimate: the provider counted {@code actualInput} tokens for a request that we estimated raw. */
    public synchronized void observe(int rawEstimate, int actualInput) {
        if (rawEstimate <= 0 || actualInput <= 0) return;
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, (double) actualInput / rawEstimate));
    }

    /** Builds the request. If the budget is exceeded, the oldest recent turns leave the request. */
    public synchronized Built build(Input in) {
        var budget = budget();
        var turns = new ArrayList<>(in.recentTurns());
        var dropped = new ArrayList<Turn>();
        while (true) {
            var request = request(in, turns, dropped);
            var raw = rawEstimate(request);
            var scaled = (int) Math.ceil(raw * scale);
            if (scaled <= budget || turns.isEmpty()) {
                return new Built(request, raw, scaled, budget, dropped.size(), scaled > budget,
                    dropped.stream().map(Turn::number).toList());
            }
            dropped.add(turns.removeFirst());
        }
    }

    /** The line that tells the model that turns left the request. */
    static String droppedNote(List<Turn> dropped) {
        var numbers = dropped.stream().map(t -> Integer.toString(t.number())).collect(Collectors.joining(", "));
        var agent = dropped.getFirst().agent().value();
        var one = dropped.size() == 1;
        return "[" + (one ? "Turn " : "Turns ") + numbers + " of " + agent + " left the context to fit the budget. "
            + "Search " + (one ? "it" : "them") + " with recall (the filter kind user finds the words of the user).]";
    }

    /** The estimate of a request before the scale. */
    public static int rawEstimate(LlmRequest request) {
        var chars = request.system().length();
        for (var tool : request.tools()) {
            chars += tool.name().length() + tool.description().length()
                + (tool.inputSchema() == null ? 0 : tool.inputSchema().toString().length());
        }
        var messages = 0;
        for (var m : request.messages()) messages += estimate(m);
        return (chars + 3) / 4 + messages;
    }

    /** The estimate of one message. */
    public static int estimate(Message message) {
        var chars = 0;
        for (var c : message.content()) {
            chars += switch (c) {
                case Text t -> t.text().length();
                case ToolUse u -> u.tool().length() + (u.input() == null ? 0 : u.input().toString().length());
                case ToolResult r -> r.output().length();
            };
        }
        return (chars + 3) / 4 + 4;
    }

    private LlmRequest request(Input in, List<Turn> turns, List<Turn> dropped) {
        var messages = new ArrayList<Message>();
        for (var turn : turns) messages.addAll(turn.messages());
        messages.addAll(in.current());
        var preamble = preamble(in, dropped);
        if (!preamble.isEmpty()) {
            if (!messages.isEmpty() && messages.getFirst().role() == Role.USER) {
                var first = messages.removeFirst();
                var content = new ArrayList<Content>();
                content.add(new Text(preamble));
                content.addAll(first.content());
                messages.addFirst(new Message(Role.USER, content));
            } else {
                messages.addFirst(Message.user(preamble));
            }
        }
        return new LlmRequest(in.system(), messages, in.tools(), maxOutputTokens);
    }

    private static String preamble(Input in, List<Turn> dropped) {
        var sb = new StringBuilder();
        if (!in.pinned().isEmpty()) {
            sb.append("[Pinned items]\n");
            for (var item : in.pinned()) sb.append(item).append("\n\n");
        }
        if (!in.older().isEmpty() || !dropped.isEmpty()) {
            sb.append("[Earlier context]\n");
            for (var entry : in.older()) sb.append(entry.render()).append("\n\n");
            if (!dropped.isEmpty()) sb.append(droppedNote(dropped)).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }
}
