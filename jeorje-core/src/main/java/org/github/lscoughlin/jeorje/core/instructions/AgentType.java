package org.github.lscoughlin.jeorje.core.instructions;

import java.nio.file.Path;
import java.util.List;

/**
 * A subagent definition.
 *
 * @param tools     tool names, or null for the default subagent toolset
 * @param isolation "worktree", "shared", or null
 * @param body      text that is added to the system prompt of the subagent
 */
public record AgentType(String name, String description, List<String> tools, String model, String isolation,
                        String body, Path file, Scopes.Scope scope) {
}
