package org.github.lscoughlin.jeorje.cli.interactive;

import java.util.Set;
import java.util.function.BiFunction;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.plan.PlanMode;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;

/**
 * The policy that the interface puts around the real policy.
 *
 * <p>For the agent of the user it enforces plan mode. The agent has a fixed tool set, so the gate denies every
 * tool that plan mode does not allow, and it allows {@code plan_submit} only in plan mode.
 *
 * <p>For subagents and workers it turns an {@code ASK} into a question to the user. Nothing else can answer the
 * {@code ASK} of a worker, so the check blocks on the worker thread until the user answers. The runtime names
 * the thread of a subagent {@code subagent-<id>}. The gate reads the agent id from that name.
 */
public final class GatedPolicy implements PermissionPolicy {
    /** Tools that Jeorje runs itself. They never ask the user. */
    static final Set<String> INTERNAL = Set.of("task_done", "story_done", "story_blocked", "story_read", "sync_done",
            "sync_abort");

    private static final String THREAD_PREFIX = "subagent-";

    private final PermissionPolicy base;
    private final PlanMode plan;
    private final BiFunction<AgentId, ToolUse, Decision> prompter;

    /**
     * @param plan the plan mode service, or null when plan mode does not apply
     * @param prompter asks the user for a worker, or null to return {@code ASK} as it is
     */
    public GatedPolicy(PermissionPolicy base, PlanMode plan, BiFunction<AgentId, ToolUse, Decision> prompter) {
        this.base = base;
        this.plan = plan;
        this.prompter = prompter;
    }

    @Override
    public Decision check(ToolUse action) {
        if (plan != null) {
            var tool = action.tool();
            if (tool.equals(PlanMode.PLAN_SUBMIT)) return plan.active() ? Decision.ALLOW : Decision.DENY;
            if (plan.active()) {
                if (!PlanMode.allows(tool)) return Decision.DENY;
                if (tool.equals("bash") && !PlanMode.bashAllowed(action.input() == null ? ""
                        : action.input().path("command").asString(""))) {
                    return Decision.DENY;
                }
            }
        }
        var decision = base.check(action);
        if (decision == Decision.ASK && prompter != null && !INTERNAL.contains(action.tool())) {
            return prompter.apply(currentAgent(), action);
        }
        return decision;
    }

    /** The agent of the calling thread, from the thread name. */
    static AgentId currentAgent() {
        var name = Thread.currentThread().getName();
        return name.startsWith(THREAD_PREFIX) ? new AgentId(name.substring(THREAD_PREFIX.length()))
                : new AgentId("unknown");
    }
}
