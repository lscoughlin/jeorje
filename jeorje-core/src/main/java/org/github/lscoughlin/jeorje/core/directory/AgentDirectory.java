package org.github.lscoughlin.jeorje.core.directory;

import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentId;

/** The view of the agents and peers that the UI needs. */
public interface AgentDirectory {
    /** The agents and peers in display order. */
    List<AgentEntry> agents();

    /**
     * The agents as a tree of harnesses, agents, and subagents. The default is empty. A directory that returns
     * an empty tree is shown as a flat list of {@link #agents()}.
     */
    default List<RosterNode> tree() {
        return List.of();
    }

    /** The full history of one agent, one display line per entry, oldest first. */
    List<String> history(AgentId agent);

    /** Sends steering text to one agent as a user input. */
    void steer(AgentId agent, String text);
}
