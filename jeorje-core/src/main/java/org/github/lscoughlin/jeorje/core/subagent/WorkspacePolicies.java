package org.github.lscoughlin.jeorje.core.subagent;

import java.nio.file.Path;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;

/**
 * Makes the policy of a subagent that works in its own directory, such as a worktree. The runtime calls it when it
 * starts a subagent with isolation {@code worktree}. The result must have the directory of the subagent as its
 * workspace and must never be wider than the policy of the parent.
 */
@FunctionalInterface
public interface WorkspacePolicies {
    /**
     * @param parent the policy of the parent agent
     * @param child the id of the new subagent
     * @param workspace the working directory of the new subagent
     * @return the policy for the subagent, before the runtime narrows it to the tools of the subagent
     */
    PermissionPolicy forWorkspace(PermissionPolicy parent, AgentId child, Path workspace);
}
