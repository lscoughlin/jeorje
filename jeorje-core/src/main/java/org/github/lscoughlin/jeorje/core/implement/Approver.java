package org.github.lscoughlin.jeorje.core.implement;

import org.github.lscoughlin.jeorje.core.plan.Plan;

/** Asks the user to approve a draft plan. The UI implements it. */
@FunctionalInterface
public interface Approver {
    /** Shows the stories of the plan and returns true if the user approves. */
    boolean approve(Plan plan);
}
