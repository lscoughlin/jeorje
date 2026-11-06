package org.github.lscoughlin.jeorje.core.implement;

import java.util.List;

/**
 * The final report of a run.
 *
 * @param status {@code done}, {@code blocked}, {@code stopped}, {@code paused}, {@code not approved}, or
 *     {@code error}
 * @param verification the result of the plan-level verification: {@code passed}, {@code failed}, or {@code skipped}
 * @param personQuestions the questions of workers that needed a person, each with the answer that the orchestrator
 *     gave
 * @param assumptions the decisions that workers stated in their summaries, one entry for each story
 */
public record ImplementReport(String plan, String status, List<StoryResult> stories, String verification,
                              String verificationOutput, String error, List<String> personQuestions,
                              List<String> assumptions) {
    public ImplementReport {
        stories = stories == null ? List.of() : List.copyOf(stories);
        personQuestions = personQuestions == null ? List.of() : List.copyOf(personQuestions);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    }

    /** A report with no list of questions and no list of assumptions. */
    public ImplementReport(String plan, String status, List<StoryResult> stories, String verification,
                           String verificationOutput, String error) {
        this(plan, status, stories, verification, verificationOutput, error, List.of(), List.of());
    }

    /** The result of one story. */
    public record StoryResult(String id, String title, StoryState state, int attempts, String result,
                              List<String> files, String reason) {
        public StoryResult {
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    public static ImplementReport failure(String plan, String status, String error) {
        return new ImplementReport(plan, status, List.of(), "skipped", "", error);
    }

    public long blockedCount() { return stories.stream().filter(s -> s.state() == StoryState.BLOCKED).count(); }

    /** The report as text. */
    public String render() {
        var sb = new StringBuilder("Plan " + plan + ": " + status + "\n");
        if (error != null && !error.isBlank()) sb.append("Error: ").append(error).append('\n');
        for (var s : stories) {
            sb.append("- ").append(s.id()).append(' ').append(s.title()).append(": ").append(s.state().label());
            if (s.attempts() > 0) sb.append(" (attempts ").append(s.attempts()).append(')');
            sb.append('\n');
            if (s.result() != null && !s.result().isBlank()) sb.append("    result: ").append(s.result()).append('\n');
            if (!s.files().isEmpty()) sb.append("    files: ").append(String.join(", ", s.files())).append('\n');
            if (s.state() == StoryState.BLOCKED && s.reason() != null && !s.reason().isBlank()) {
                sb.append("    reason: ").append(s.reason()).append('\n');
            }
        }
        if (!personQuestions.isEmpty()) {
            sb.append("Questions that needed a person:\n");
            personQuestions.forEach(q -> sb.append("- ").append(q).append('\n'));
        }
        if (!assumptions.isEmpty()) {
            sb.append("Assumptions of the workers:\n");
            assumptions.forEach(a -> sb.append("- ").append(a).append('\n'));
        }
        sb.append("Plan verification: ").append(verification).append('\n');
        if (verification.equals("failed") && verificationOutput != null && !verificationOutput.isBlank()) {
            sb.append(verificationOutput).append('\n');
        }
        return sb.toString();
    }
}
