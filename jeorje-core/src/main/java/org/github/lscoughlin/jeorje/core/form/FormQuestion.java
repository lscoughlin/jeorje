package org.github.lscoughlin.jeorje.core.form;

import java.util.List;
import java.util.Objects;

/**
 * One question of a form.
 *
 * @param id the id that the answer uses
 * @param kind the kind of answer
 * @param options the options of a {@code SINGLE} or {@code MULTI} question, otherwise empty
 * @param allowOther true when the user can type an answer that is not an option
 * @param required true when the user must answer before the form can end
 * @param minChoices the least number of choices of a {@code MULTI} question
 * @param maxChoices the most choices of a {@code MULTI} question, or 0 for no limit
 * @param defaultValue the label of the option that is chosen at the start (SINGLE), the text of a TEXT field, or null
 */
public record FormQuestion(String id, String prompt, Kind kind, List<FormOption> options, boolean allowOther,
                           boolean required, int minChoices, int maxChoices, String defaultValue) {
    /** The kind of answer. */
    public enum Kind { SINGLE, MULTI, TEXT }

    public FormQuestion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(kind, "kind");
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** True for a question with options. */
    public boolean hasOptions() { return kind != Kind.TEXT; }

    /** The labels of the options. */
    public List<String> labels() { return options.stream().map(FormOption::label).toList(); }
}
