package org.github.lscoughlin.jeorje.core.subagent;

import java.util.Set;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;

/** Makes the policy of a subagent. It is never wider than the policy of the parent. */
public final class PolicyNarrowing {
    private PolicyNarrowing() {}

    /** The stricter of two decisions: DENY is stricter than ASK, and ASK is stricter than ALLOW. */
    public static Decision stricter(Decision a, Decision b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /**
     * A policy that gives the stricter decision of the parent policy and the extra policy. A tool that is not in
     * {@code allowedTools} is always denied.
     *
     * @param extra a policy that can only narrow, or null
     */
    public static PermissionPolicy narrow(PermissionPolicy parent, PermissionPolicy extra, Set<String> allowedTools) {
        return (ToolUse action) -> {
            if (!allowedTools.contains(action.tool())) return Decision.DENY;
            var decision = parent.check(action);
            if (extra != null) decision = stricter(decision, extra.check(action));
            return decision;
        };
    }
}
