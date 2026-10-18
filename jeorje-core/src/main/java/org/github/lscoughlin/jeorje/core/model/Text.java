package org.github.lscoughlin.jeorje.core.model;

import java.util.Objects;

/** Plain text. */
public record Text(String text) implements Content {
    public Text { Objects.requireNonNull(text, "text"); }
}
