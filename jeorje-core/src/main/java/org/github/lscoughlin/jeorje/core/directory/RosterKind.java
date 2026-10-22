package org.github.lscoughlin.jeorje.core.directory;

/** The level of a {@link RosterNode}. */
public enum RosterKind {
    /** One instance of Jeorje: this one or a peer of the swarm. */
    HARNESS,
    /** An agent of a harness. */
    AGENT,
    /** A subagent of an agent or of another subagent. */
    SUBAGENT
}
