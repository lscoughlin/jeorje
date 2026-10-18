package org.github.lscoughlin.jeorje.core.subagent;

import org.github.lscoughlin.jeorje.core.model.AgentId;

/**
 * A snapshot of one agent for {@code agent_status} and the UI.
 *
 * @param parent the id of the parent, or null for a root agent
 * @param storyId the story id, or null
 * @param task the task text, or an empty string for a root agent
 * @param waitingFor the name of the agent that this agent waits for, or null
 * @param lastOutput the last output line
 * @param branch the branch, or null. The worktree story sets it.
 */
public record AgentInfo(AgentId id, String name, AgentId parent, String storyId, AgentLifecycle state,
                        String task, String waitingFor, String lastOutput, int depth, SpawnMode mode,
                        String branch) {}
