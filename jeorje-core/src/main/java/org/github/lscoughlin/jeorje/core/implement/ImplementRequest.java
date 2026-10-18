package org.github.lscoughlin.jeorje.core.implement;

import java.nio.file.Path;
import java.util.Set;

/**
 * The input of {@code /implement}.
 *
 * @param plan the plan file
 * @param only the story ids to run, or empty for all open stories. Their open dependencies run too.
 * @param yes true for {@code --yes} or {@code --yolo}: approve a draft plan without a question
 */
public record ImplementRequest(Path plan, Set<String> only, boolean yes) {
    public ImplementRequest {
        only = only == null ? Set.of() : Set.copyOf(only);
    }

    public static ImplementRequest of(Path plan) { return new ImplementRequest(plan, Set.of(), false); }

    public ImplementRequest withYes(boolean value) { return new ImplementRequest(plan, only, value); }

    public ImplementRequest withOnly(Set<String> value) { return new ImplementRequest(plan, value, yes); }
}
