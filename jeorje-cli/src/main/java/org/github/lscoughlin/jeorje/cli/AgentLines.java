package org.github.lscoughlin.jeorje.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;

/**
 * Turns the events of agents into plain text lines for a remote client. Streamed text collects in a buffer per
 * agent and goes out when a line is complete or the turn ends. A permission request becomes a line that names
 * the request id, so that the client can answer it.
 */
final class AgentLines {
    private static final int RESULT_LIMIT = 160;

    private final Map<AgentId, StringBuilder> partial = new HashMap<>();

    /** The lines that the event completes. The list is empty when the event adds nothing. */
    synchronized List<String> render(AgentEvent event) {
        var out = new ArrayList<String>();
        switch (event) {
            case AgentEvent.TokenDelta d -> {
                var buffer = partial.computeIfAbsent(d.agent(), k -> new StringBuilder());
                buffer.append(d.text());
                int newline;
                while ((newline = buffer.indexOf("\n")) >= 0) {
                    out.add(buffer.substring(0, newline));
                    buffer.delete(0, newline + 1);
                }
            }
            case AgentEvent.ToolStarted s -> {
                flush(s.agent(), out);
                out.add("[" + s.tool() + "] " + (s.summary() == null ? "" : s.summary()));
            }
            case AgentEvent.ToolFinished f -> {
                flush(f.agent(), out);
                var text = f.result().output() == null ? "" : f.result().output().strip();
                var first = text.isEmpty() ? "" : text.lines().findFirst().orElse("");
                if (first.length() > RESULT_LIMIT) first = first.substring(0, RESULT_LIMIT) + "...";
                out.add((f.result().error() ? "  ! " : "  = ") + first);
            }
            case AgentEvent.ConfirmationRequested c -> {
                flush(c.agent(), out);
                out.add("? approval needed for " + c.toolUse().tool() + " (request " + c.toolUse().id()
                        + "). Answer yes or no.");
            }
            case AgentEvent.TurnFinished t -> flush(t.agent(), out);
            case AgentEvent.Error e -> {
                flush(e.agent(), out);
                out.add("! " + e.message());
            }
            case AgentEvent.Cleared c -> {
                partial.remove(c.agent());
                out.add("New conversation.");
            }
            default -> { }
        }
        return out;
    }

    private void flush(AgentId agent, List<String> out) {
        var buffer = partial.get(agent);
        if (buffer != null && !buffer.isEmpty()) {
            out.add(buffer.toString());
            buffer.setLength(0);
        }
    }
}
