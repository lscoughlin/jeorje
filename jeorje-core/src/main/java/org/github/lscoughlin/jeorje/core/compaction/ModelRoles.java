package org.github.lscoughlin.jeorje.core.compaction;

import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;

/**
 * The three model roles. When no compactor is set, compaction uses the fallback pruner. When no advisor is
 * set, Jeorje does not register the {@code advisor} tool.
 */
public record ModelRoles(String defaultModel, String compactor, String advisor) {
    public static ModelRoles from(JeorjeConfig.Models models) {
        return new ModelRoles(models.defaultModel(), models.compactor(), models.advisor());
    }

    private static boolean set(String s) { return s != null && !s.isBlank(); }

    public boolean compactorSet() { return set(compactor); }

    public boolean advisorSet() { return set(advisor); }

    /** True when the {@code advisor} tool is registered. */
    public boolean advisorToolEnabled() { return advisorSet(); }

    /** The model that the pruner uses, or empty when the fallback pruner runs. */
    public java.util.Optional<String> pruningModel() {
        return compactorSet() ? java.util.Optional.of(compactor) : java.util.Optional.empty();
    }
}
