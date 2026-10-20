package org.github.lscoughlin.jeorje.core.form;

import java.util.ArrayList;
import java.util.List;

/**
 * The answer of the user to a form.
 *
 * @param status how the form ended
 * @param answers the answers that exist, also for a form that ended with {@code CHAT}
 * @param note a note of the user for the agent, or null
 */
public record FormAnswer(Status status, List<QuestionAnswer> answers, String note) {
    /** How a form ended. */
    public enum Status { ANSWERED, CHAT, DECLINED }

    /**
     * The answer to one question.
     *
     * @param selected the labels of the chosen options
     * @param other the text of an "other" answer, or null
     * @param text the text of a free text answer, or null
     */
    public record QuestionAnswer(String id, List<String> selected, String other, String text) {
        public QuestionAnswer {
            selected = selected == null ? List.of() : List.copyOf(selected);
        }

        /** True when the question has an answer. */
        public boolean answered() {
            return !selected.isEmpty() || other != null && !other.isBlank() || text != null && !text.isBlank();
        }

        /** The answer as one text: the labels, then the other text, or the free text. */
        public String value() {
            var parts = new ArrayList<>(selected);
            if (other != null && !other.isBlank()) parts.add(other);
            if (text != null && !text.isBlank()) parts.add(text);
            return String.join("; ", parts);
        }
    }

    public FormAnswer {
        answers = answers == null ? List.of() : List.copyOf(answers);
    }

    /** A free text answer to the first question. Used for a plain string answer. */
    public static FormAnswer text(String text) {
        return new FormAnswer(Status.ANSWERED, List.of(new QuestionAnswer("q1", List.of(), null, text)), null);
    }

    public static FormAnswer declined() { return new FormAnswer(Status.DECLINED, List.of(), null); }

    /** The first answer as one text, or an empty text. */
    public String firstValue() { return answers.isEmpty() ? "" : answers.getFirst().value(); }

    /** The text for the history and for the agent. */
    public String render(Form form) {
        return switch (status) {
            case DECLINED -> "The user declined to answer.";
            case ANSWERED -> "User answers:\n" + lines(form, false);
            case CHAT -> {
                var sb = new StringBuilder("The user wants to talk about this before answering.");
                if (note != null && !note.isBlank()) sb.append("\nNote from the user: ").append(note.strip());
                var open = form.questions().stream().filter(q -> answers.stream()
                    .noneMatch(a -> a.id().equals(q.id()) && a.answered())).toList();
                if (!open.isEmpty()) {
                    sb.append("\nOpen questions:");
                    for (var q : open) sb.append("\n- ").append(q.id()).append(" \"").append(q.prompt()).append('"');
                }
                if (answers.stream().anyMatch(QuestionAnswer::answered)) {
                    sb.append("\nAnswers so far:\n").append(lines(form, true));
                }
                sb.append("\nAnswer in chat. Then call ask_user again if you still need an answer.");
                yield sb.toString();
            }
        };
    }

    private String lines(Form form, boolean onlyAnswered) {
        var out = new ArrayList<String>();
        for (var q : form.questions()) {
            var a = answers.stream().filter(x -> x.id().equals(q.id())).findFirst().orElse(null);
            var answered = a != null && a.answered();
            if (onlyAnswered && !answered) continue;
            out.add("- " + q.id() + " \"" + q.prompt() + "\": " + (answered ? a.value() : "(no answer)"));
        }
        return String.join("\n", out);
    }
}
