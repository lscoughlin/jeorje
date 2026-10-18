package org.github.lscoughlin.jeorje.core.directory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.session.TranscriptReader;

/** Reads the history lines of one agent from {@code transcript.jsonl}. */
public final class TranscriptHistory {
    private TranscriptHistory() {}

    /** The lines of all complete turns of the agent, in file order. */
    public static List<String> read(Path transcript, AgentId agent) {
        var out = new ArrayList<String>();
        for (var timed : TranscriptReader.read(transcript)) {
            var turn = timed.turn();
            if (!turn.agent().equals(agent)) continue;
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
}
