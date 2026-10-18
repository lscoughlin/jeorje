package org.github.lscoughlin.jeorje.core.directory;

import org.github.lscoughlin.jeorje.core.model.AgentId;

/**
 * One row of the agent list.
 *
 * @param parent the id of the parent, or null for a root agent
 * @param state the state name, for example {@code RUNNING}
 * @param peer the name of the peer for a remote agent, or null for a local agent
 * @param branch the git branch, or null
 * @param lastOutput the last output line, or an empty string
 */
public record AgentEntry(AgentId id, String name, AgentId parent, String state, String peer, String branch,
                         String lastOutput) {
    public AgentEntry {
        lastOutput = lastOutput == null ? "" : lastOutput;
    }

    public AgentEntry withState(String newState) {
        return new AgentEntry(id, name, parent, newState, peer, branch, lastOutput);
    }

    public AgentEntry withLastOutput(String line) {
        return new AgentEntry(id, name, parent, state, peer, branch, line);
    }
}
