package org.github.lscoughlin.jeorje.core.form;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A form that an agent shows to the user: one or more questions.
 *
 * @param title a short title, or null
 */
public record Form(String title, List<FormQuestion> questions) {
    /** The most questions in a form. */
    public static final int MAX_QUESTIONS = 8;
    /** The most options of a question. */
    public static final int MAX_OPTIONS = 12;

    public Form {
        questions = List.copyOf(Objects.requireNonNull(questions, "questions"));
    }

    /** A form with one question. Without choices the question is a free text question. */
    public static Form single(String question, List<String> choices) {
        var options = choices == null ? List.<FormOption>of() : choices.stream().map(FormOption::of).toList();
        var kind = options.isEmpty() ? FormQuestion.Kind.TEXT : FormQuestion.Kind.SINGLE;
        return new Form(null, List.of(new FormQuestion("q1", question, kind, options, false, true, 0, 0, null)));
    }

    /** The prompt of the first question, or an empty text. */
    public String firstPrompt() { return questions.isEmpty() ? "" : questions.getFirst().prompt(); }

    /** Checks the form. Returns the reason for the first problem, or empty when the form is valid. */
    public Optional<String> validate() {
        if (questions.isEmpty()) return Optional.of("The form has no question.");
        if (questions.size() > MAX_QUESTIONS) {
            return Optional.of("The form has " + questions.size() + " questions. The most is " + MAX_QUESTIONS + ".");
        }
        var ids = new HashSet<String>();
        for (var q : questions) {
            if (q.id().isBlank() || !ids.add(q.id())) return Optional.of("Question ids must be unique: " + q.id());
            if (q.prompt().isBlank()) return Optional.of("Question " + q.id() + " has an empty prompt.");
            var problem = validate(q);
            if (problem.isPresent()) return problem;
        }
        return Optional.empty();
    }

    private static Optional<String> validate(FormQuestion q) {
        if (q.kind() == FormQuestion.Kind.TEXT) {
            if (!q.options().isEmpty()) return Optional.of("Question " + q.id() + " is a text question and has options.");
            return Optional.empty();
        }
        var n = q.options().size();
        if (n < 2) return Optional.of("Question " + q.id() + " needs at least 2 options.");
        if (n > MAX_OPTIONS) return Optional.of("Question " + q.id() + " has " + n + " options. The most is " + MAX_OPTIONS + ".");
        var labels = new HashSet<String>();
        for (var o : q.options()) {
            if (o.label().isBlank()) return Optional.of("Question " + q.id() + " has an option with an empty label.");
            if (!labels.add(o.label())) return Optional.of("Question " + q.id() + " has the option " + o.label() + " twice.");
        }
        if (q.kind() == FormQuestion.Kind.MULTI) {
            if (q.minChoices() < 0 || q.maxChoices() < 0 || q.maxChoices() > n
                || q.maxChoices() != 0 && q.minChoices() > q.maxChoices() || q.minChoices() > n) {
                return Optional.of("Question " + q.id() + " has bad minChoices or maxChoices. Use 0 <= min <= max <= "
                    + n + ".");
            }
        } else if (q.minChoices() != 0 || q.maxChoices() != 0) {
            return Optional.of("Question " + q.id() + " is not a multi question. Remove minChoices and maxChoices.");
        }
        if (q.defaultValue() != null && q.kind() == FormQuestion.Kind.SINGLE && !q.labels().contains(q.defaultValue())) {
            return Optional.of("The default of question " + q.id() + " is not one of its options.");
        }
        return Optional.empty();
    }
}
