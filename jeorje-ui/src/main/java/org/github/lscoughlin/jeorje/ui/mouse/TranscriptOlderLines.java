package org.github.lscoughlin.jeorje.ui.mouse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.session.TranscriptReader;

/** Reads the full text of the lines from {@code transcript.jsonl}, also for tool results. */
public final class TranscriptOlderLines implements OlderLines {
    private final Path file;
    private final AgentId agent;

    /** @param agent the agent whose turns the view shows, or null for all agents */
    public TranscriptOlderLines(Path file, AgentId agent) {
        this.file = file;
        this.agent = agent;
    }

    /** All lines of the transcript. */
    public List<String> allLines() {
        var out = new ArrayList<String>();
        for (var timed : TranscriptReader.read(file)) {
            var turn = timed.turn();
            if (agent != null && !agent.equals(turn.agent())) continue;
            for (var message : turn.messages()) {
                for (var content : message.content()) {
                    switch (content) {
                        case Text t -> {
                            var prefix = message.role() == Role.USER ? "> " : "";
                            for (var line : t.text().split("\n", -1)) out.add(prefix + line);
                        }
                        case ToolUse u -> out.add("[" + u.tool() + "]");
                        case ToolResult r -> out.addAll(List.of(r.output().split("\n", -1)));
                    }
                }
            }
        }
        return out;
    }

    public int lineCount() { return allLines().size(); }

    @Override
    public List<String> before(int index, int count) {
        var all = allLines();
        var end = Math.clamp(index, 0, all.size());
        return List.copyOf(all.subList(Math.max(0, end - count), end));
    }
}
