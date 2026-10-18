package org.github.lscoughlin.jeorje.core.subagent;

import org.github.lscoughlin.jeorje.core.model.AgentId;

/**
 * The end result of a subagent.
 *
 * @param reason the reason when the outcome is not {@code COMPLETED}, else null
 * @param summary the completion summary with the labeled parts
 * @param finalText the last assistant text of the subagent
 */
public record Completion(AgentId agent, String name, Outcome outcome, String reason, String summary,
                         String finalText) {
    /** The outcome of a subagent. */
    public enum Outcome { COMPLETED, FAILED, CANCELLED }

    /** The text for the parent: the summary. */
    public String text() { return summary; }
}
