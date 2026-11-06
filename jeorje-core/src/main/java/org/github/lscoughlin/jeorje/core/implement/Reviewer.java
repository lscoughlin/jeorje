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

    /**
     * The verdict of a review.
     *
     * @param kind approved, rejected with problems, or unavailable
     * @param problems the problems of a rejection, word for word
     * @param reason why the review is unavailable, or an empty string
     */
    record Verdict(Kind kind, List<String> problems, String reason) {
        /** The kind of verdict. */
        public enum Kind { APPROVED, REJECTED, UNAVAILABLE }

        public Verdict {
            problems = problems == null ? List.of() : List.copyOf(problems);
            reason = reason == null ? "" : reason;
        }

        public static Verdict approve() { return new Verdict(Kind.APPROVED, List.of(), ""); }

        /** An answer that lists problems. It is a failed review. */
        public static Verdict reject(List<String> problems) { return new Verdict(Kind.REJECTED, problems, ""); }

        /** No review happened: the answer was blank, or the call failed. It is not a failed review. */
        public static Verdict unavailable(String reason) { return new Verdict(Kind.UNAVAILABLE, List.of(), reason); }

        public boolean approved() { return kind == Kind.APPROVED; }

        public boolean unavailable() { return kind == Kind.UNAVAILABLE; }
    }

    Verdict review(Request request);
}
