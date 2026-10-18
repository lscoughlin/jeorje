package org.github.lscoughlin.jeorje.core.implement;

import java.util.List;

/** Reviews the result of a story against its acceptance criteria. The advisor model does it. */
@FunctionalInterface
public interface Reviewer {
    /**
     * What the reviewer gets.
     *
     * @param storyText the text of the story
     * @param draftSummary the draft summary of {@code story_done}
     * @param changedFiles the changed files
     * @param diff the diff of the story, possibly cut
     */
    record Request(String storyId, String storyText, String draftSummary, List<String> changedFiles, String diff) {}

    /** The verdict: approved, or a list of problems. */
    record Verdict(boolean approved, List<String> problems) {
        public Verdict {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        public static Verdict approve() { return new Verdict(true, List.of()); }

        public static Verdict reject(List<String> problems) { return new Verdict(false, problems); }
    }

    Verdict review(Request request);
}
