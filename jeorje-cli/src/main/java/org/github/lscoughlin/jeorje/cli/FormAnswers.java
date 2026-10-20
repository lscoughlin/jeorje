package org.github.lscoughlin.jeorje.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.form.Form;
import org.github.lscoughlin.jeorje.core.form.FormAnswer;
import org.github.lscoughlin.jeorje.core.form.FormAnswer.QuestionAnswer;
import org.github.lscoughlin.jeorje.core.form.FormQuestion;

/** Answers a form with no user at the terminal: from {@code --answer} values and from the {@code --yes} rule. */
final class FormAnswers {
    /** The answer to a text question under {@code --yes} when the question has no default. */
    static final String BEST_JUDGMENT = "Use your best judgment and go on.";

    private FormAnswers() {}

    /** Parses values of the form {@code id=value}. A repeated id keeps the last value. */
    static Map<String, String> parse(List<String> raw) {
        var out = new java.util.LinkedHashMap<String, String>();
        for (var item : raw == null ? List.<String>of() : raw) {
            var eq = item.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("Bad --answer value: " + item + ". Use <id>=<value>.");
            out.put(item.substring(0, eq).strip(), item.substring(eq + 1).strip());
        }
        return out;
    }

    /**
     * Answers every question of the form.
     *
     * @param given the values of {@code --answer}, by question id
     * @param yes true to answer a question that has no value with its default, its first options, or the best
     *            judgment text
     * @return the answer, or empty when a question has no value and no rule applies
     * @throws IllegalArgumentException when a given value is not valid for its question
     */
    static Optional<FormAnswer> resolve(Form form, Map<String, String> given, boolean yes) {
        var answers = new ArrayList<QuestionAnswer>();
        for (var q : form.questions()) {
            var value = given.get(q.id());
            if (value != null) {
                answers.add(fromValue(q, value));
            } else if (q.defaultValue() != null) {
                answers.add(fromValue(q, q.defaultValue()));
            } else if (yes) {
                answers.add(automatic(q));
            } else if (!q.required()) {
                continue;
            } else {
                return Optional.empty();
            }
        }
        return Optional.of(new FormAnswer(FormAnswer.Status.ANSWERED, answers, null));
    }

    private static QuestionAnswer automatic(FormQuestion q) {
        return switch (q.kind()) {
            case TEXT -> new QuestionAnswer(q.id(), List.of(), null, BEST_JUDGMENT);
            case SINGLE -> new QuestionAnswer(q.id(), List.of(q.options().getFirst().label()), null, null);
            case MULTI -> {
                var n = Math.max(q.minChoices(), q.required() ? 1 : 0);
                yield new QuestionAnswer(q.id(), q.labels().subList(0, Math.min(n, q.options().size())), null, null);
            }
        };
    }

    /** Turns a text value into an answer. An option is a label or a number from 1. */
    static QuestionAnswer fromValue(FormQuestion q, String value) {
        if (q.kind() == FormQuestion.Kind.TEXT) return new QuestionAnswer(q.id(), List.of(), null, value);
        var parts = q.kind() == FormQuestion.Kind.MULTI ? List.of(value.split("\\s*,\\s*")) : List.of(value);
        var selected = new ArrayList<String>();
        String other = null;
        for (var part : parts) {
            var label = option(q, part);
            if (label != null) {
                if (!selected.contains(label)) selected.add(label);
            } else if (q.allowOther() && !part.isBlank()) {
                other = part;
            } else {
                throw new IllegalArgumentException("Question " + q.id() + ": '" + part + "' is not one of "
                        + String.join(", ", q.labels()) + ".");
            }
        }
        if (q.kind() == FormQuestion.Kind.SINGLE && selected.size() + (other == null ? 0 : 1) > 1) {
            throw new IllegalArgumentException("Question " + q.id() + " takes one answer.");
        }
        var count = selected.size() + (other == null ? 0 : 1);
        if (q.kind() == FormQuestion.Kind.MULTI && (count < q.minChoices() || q.maxChoices() > 0 && count > q.maxChoices())) {
            throw new IllegalArgumentException("Question " + q.id() + ": choose " + q.minChoices() + " to "
                    + (q.maxChoices() > 0 ? q.maxChoices() : q.options().size()) + " options.");
        }
        return new QuestionAnswer(q.id(), selected, other, null);
    }

    private static String option(FormQuestion q, String part) {
        for (var o : q.options()) if (o.label().equals(part)) return o.label();
        try {
            var n = Integer.parseInt(part.strip());
            if (n >= 1 && n <= q.options().size()) return q.options().get(n - 1).label();
        } catch (NumberFormatException ignored) {
            // not a number
        }
        return null;
    }

    /** The form as numbered text, for a run that stops to ask. */
    static String render(Form form) {
        var sb = new StringBuilder();
        if (form.title() != null && !form.title().isBlank()) sb.append(form.title()).append('\n');
        for (var q : form.questions()) {
            sb.append("Question ").append(q.id()).append(" (").append(q.kind().name().toLowerCase(java.util.Locale.ROOT))
                    .append(q.required() ? "" : ", optional").append("): ").append(q.prompt()).append('\n');
            for (var i = 0; i < q.options().size(); i++) {
                var o = q.options().get(i);
                sb.append("  ").append(i + 1).append(". ").append(o.label());
                if (o.description() != null && !o.description().isBlank()) sb.append(" - ").append(o.description());
                sb.append('\n');
            }
            if (q.allowOther()) sb.append("  or your own answer\n");
        }
        return sb.toString();
    }
}
