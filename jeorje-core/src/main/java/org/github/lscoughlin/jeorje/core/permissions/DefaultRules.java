package org.github.lscoughlin.jeorje.core.permissions;

import java.util.Map;
import java.util.Set;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;

/** The default decisions from the design table. */
public final class DefaultRules {

    /** Read-only tools. They are allowed inside the workspace. */
    public static final Set<String> READ_TOOLS =
            Set.of("read", "ls", "glob", "grep", "diff", "recall", "agent_status");

    /** Tools that change files. */
    public static final Set<String> WRITE_TOOLS = Set.of("write", "edit", "patch");

    /** The name prefix of every MCP tool. */
    public static final String MCP_PREFIX = "mcp__";

    private static final Map<String, Decision> TABLE = Map.ofEntries(
            Map.entry("write", Decision.ASK),
            Map.entry("edit", Decision.ASK),
            Map.entry("patch", Decision.ASK),
            Map.entry("bash", Decision.ASK),
            Map.entry("webfetch", Decision.ASK),
            Map.entry("spawn_agent", Decision.ALLOW),
            Map.entry("agent_message", Decision.ALLOW),
            Map.entry("agent_ask", Decision.ALLOW),
            Map.entry("agent_answer", Decision.ALLOW),
            Map.entry("story_read", Decision.ALLOW),
            Map.entry("skill", Decision.ALLOW),
            Map.entry("worktree_merge", Decision.ASK),
            Map.entry("worktree_discard", Decision.ASK),
            Map.entry("todo", Decision.ALLOW),
            Map.entry("ask_user", Decision.ALLOW),
            Map.entry("advisor", Decision.ALLOW),
            Map.entry("plan_submit", Decision.ALLOW));

    private DefaultRules() {
    }

    /** The default for a tool. An unknown tool gets ASK. */
    public static Decision forTool(String tool) {
        if (READ_TOOLS.contains(tool)) return Decision.ALLOW;
        return TABLE.getOrDefault(tool, Decision.ASK);
    }
}
