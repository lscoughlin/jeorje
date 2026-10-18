package org.github.lscoughlin.jeorje.core.implement;

import java.util.List;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.core.worktree.WorktreeManager;

/**
 * Makes the tools that only the workers of {@code /implement} have: the story tools, the sync tools, and the agent
 * tools. The tools module implements it. The tools of the orchestrator are the base tools without
 * {@code spawn_agent}, {@code plan_submit}, and {@code ask_user}, plus these tools.
 */
@FunctionalInterface
public interface WorkerToolsFactory {
    /** What the factory gets. {@code worktrees} is null when the run has no git support. */
    record Context(StoryHost host, SubagentRuntime runtime, WorktreeManager worktrees, String sessionId) {}

    List<Tool> create(Context context);
}
