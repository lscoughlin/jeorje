package org.github.lscoughlin.jeorje.core.subagent;

import java.time.Duration;

/**
 * The limits of the subagent runtime.
 *
 * @param maxConcurrent subagents that run at the same time for each instance (default 8)
 * @param maxDepth the nesting depth of subagents (default 2)
 * @param maxModelCalls model calls for each subagent task (default 100)
 * @param maxDuration wall-clock time for each subagent task (default 30 minutes)
 * @param askTimeout the time that {@code agent_ask} waits for an answer (default 120 s)
 * @param maxAsks questions that one agent can ask in one task (default 10)
 */
public record SubagentLimits(int maxConcurrent, int maxDepth, int maxModelCalls, Duration maxDuration,
                             Duration askTimeout, int maxAsks) {
    public SubagentLimits {
        if (maxConcurrent < 1 || maxDepth < 1 || maxModelCalls < 1 || maxAsks < 0) {
            throw new IllegalArgumentException("limits must be positive");
        }
    }

    /** The limits of the design. */
    public static SubagentLimits defaults() {
        return new SubagentLimits(8, 2, 100, Duration.ofMinutes(30), Duration.ofSeconds(120), 10);
    }

    public SubagentLimits withMaxConcurrent(int value) {
        return new SubagentLimits(value, maxDepth, maxModelCalls, maxDuration, askTimeout, maxAsks);
    }

    public SubagentLimits withMaxDepth(int value) {
        return new SubagentLimits(maxConcurrent, value, maxModelCalls, maxDuration, askTimeout, maxAsks);
    }

    public SubagentLimits withMaxModelCalls(int value) {
        return new SubagentLimits(maxConcurrent, maxDepth, value, maxDuration, askTimeout, maxAsks);
    }

    public SubagentLimits withMaxDuration(Duration value) {
        return new SubagentLimits(maxConcurrent, maxDepth, maxModelCalls, value, askTimeout, maxAsks);
    }

    public SubagentLimits withAskTimeout(Duration value) {
        return new SubagentLimits(maxConcurrent, maxDepth, maxModelCalls, maxDuration, value, maxAsks);
    }

    public SubagentLimits withMaxAsks(int value) {
        return new SubagentLimits(maxConcurrent, maxDepth, maxModelCalls, maxDuration, askTimeout, value);
    }
}
