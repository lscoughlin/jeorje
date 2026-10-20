package org.github.lscoughlin.jeorje.core.form;

import java.util.Objects;

/**
 * One option of a choice question.
 *
 * @param description a short explanation, or null
 */
public record FormOption(String label, String description) {
    public FormOption {
        Objects.requireNonNull(label, "label");
    }

    public static FormOption of(String label) { return new FormOption(label, null); }
}
