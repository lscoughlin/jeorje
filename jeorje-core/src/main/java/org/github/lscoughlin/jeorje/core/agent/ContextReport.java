package org.github.lscoughlin.jeorje.core.agent;

import java.util.List;
import java.util.Locale;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.Turn;

/**
 * How full the context of an agent was at its last model call. The numbers are estimates: one token for four
 * characters, before the scale that the agent learns from the provider.
 *
 * @param agent the name of the agent
 * @param system the tokens of the system prompt
 * @param tools the tokens of the tool definitions
 * @param pinned the tokens of the pinned items, with the decisions of the user
 * @param earlier the tokens of the earlier context
 * @param earlierChunks the number of chunks in the earlier context
 * @param earlierPruned the number of those chunks that are pruned
 * @param recent the tokens of the recent turns
 * @param recentTurns the number of recent turns
 * @param current the tokens of the turn that runs
 * @param request the estimate of the whole request, with the scale
 * @param budget the budget of the working context
 * @param spilled the number of tool results that the agent moved out of a running turn, since it started
 * @param droppedTurns the numbers of the turns that left the last request
 */
public record ContextReport(String agent, int system, int tools, int pinned, int earlier, int earlierChunks,
                            int earlierPruned, int recent, int recentTurns, int current, int request, int budget,
                            int spilled, List<Integer> droppedTurns) {
    public ContextReport {
        droppedTurns = List.copyOf(droppedTurns);
    }

    /** The share of the budget that the request used, as a percent. */
    public int percent() {
        return budget <= 0 ? 0 : (int) Math.min(999, Math.round(request * 100.0 / budget));
    }

    static ContextReport of(String agent, ContextBuilder.Input in, ContextBuilder.Built built, int spilled) {
        var system = tokens(in.system().length());
        var tools = 0;
        for (var t : in.tools()) tools += toolTokens(t);
        var pinned = 0;
        for (var p : in.pinned()) pinned += tokens(p.length());
        var earlier = 0;
        var pruned = 0;
        for (var e : in.older()) {
            earlier += e.tokens();
            if (e.level() > 0) pruned++;
        }
        var keptTurns = in.recentTurns().subList(Math.min(built.droppedTurns(), in.recentTurns().size()),
            in.recentTurns().size());
        var kept = keptTurns.size();
        var recent = 0;
        for (var turn : keptTurns) recent += turnTokens(turn);
        var current = 0;
        for (var m : in.current()) current += ContextBuilder.estimate(m);
        return new ContextReport(agent, system, tools, pinned, earlier, in.older().size(), pruned, recent,
            kept, current, built.estimate(), built.budget(), spilled, built.droppedTurnNumbers());
    }

    private static int toolTokens(ToolDefinition t) {
        var chars = t.name().length() + t.description().length()
            + (t.inputSchema() == null ? 0 : t.inputSchema().toString().length());
        return tokens(chars);
    }

    private static int turnTokens(Turn turn) {
        var sum = 0;
        for (Message m : turn.messages()) sum += ContextBuilder.estimate(m);
        return sum;
    }

    private static int tokens(int chars) { return (chars + 3) / 4; }

    /** The report as lines of text, for the {@code /context} command. */
    public String render() {
        var sb = new StringBuilder();
        sb.append("Context of ").append(agent).append(" at the last model call (estimates):\n");
        row(sb, "system prompt", system, "");
        row(sb, "tool definitions", tools, "");
        row(sb, "pinned items", pinned, "");
        row(sb, "earlier context", earlier, earlierChunks + " chunks, " + earlierPruned + " pruned");
        row(sb, "recent turns", recent, recentTurns + (recentTurns == 1 ? " turn" : " turns"));
        row(sb, "current turn", current, "");
        sb.append(String.format(Locale.ROOT, "  %-17s%,9d of %,d tokens (%d%%)%n", "request", request, budget,
            percent()));
        sb.append("Results moved out of a running turn: ").append(spilled).append('\n');
        sb.append("Turns that left the last request: ")
            .append(droppedTurns.isEmpty() ? "none" : droppedTurns.toString());
        return sb.toString();
    }

    private static void row(StringBuilder sb, String name, int tokens, String detail) {
        sb.append(String.format(Locale.ROOT, "  %-17s%,9d tokens%s%n", name, tokens,
            detail.isEmpty() ? "" : "  (" + detail + ")"));
    }
}
