package org.github.lscoughlin.jeorje.core.subagent;

import org.github.lscoughlin.jeorje.core.model.AgentId;

/** An event of the subagent runtime. The UI uses these events for the agent list. */
public sealed interface SubagentEvent {
    AgentId agent();

    /** A subagent exists. */
    record Spawned(AgentId agent, AgentId parent, String name, int depth) implements SubagentEvent {}

    /** The life cycle state of an agent changed. */
    record StateChanged(AgentId agent, String name, AgentLifecycle from, AgentLifecycle to, String reason)
        implements SubagentEvent {}

    /** A subagent ended and its completion summary exists. */
    record Finished(AgentId agent, String name, Completion completion) implements SubagentEvent {}
}
