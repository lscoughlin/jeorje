package org.github.lscoughlin.jeorje.testkit;

import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A {@link PermissionPolicy} that returns one set decision and records the actions. */
public final class FixedPolicy implements PermissionPolicy {
    private final Decision decision;
    private final List<ToolUse> checked = new CopyOnWriteArrayList<>();

    public FixedPolicy(Decision decision) {
        this.decision = decision;
    }

    public static FixedPolicy allow() { return new FixedPolicy(Decision.ALLOW); }

    public static FixedPolicy ask() { return new FixedPolicy(Decision.ASK); }

    public static FixedPolicy deny() { return new FixedPolicy(Decision.DENY); }

    @Override
    public Decision check(ToolUse action) {
        checked.add(action);
        return decision;
    }

    /** The actions that were checked, in order. */
    public List<ToolUse> checked() { return new ArrayList<>(checked); }
}
