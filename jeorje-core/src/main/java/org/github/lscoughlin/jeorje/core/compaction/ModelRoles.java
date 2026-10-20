package org.github.lscoughlin.jeorje.core.compaction;

import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;

/**
 * The three model roles. When no compactor is set, compaction uses the fallback pruner.
 *
 * <p>The advisor is on by default. An empty advisor value means {@link #DEFAULT_ADVISOR}. The value {@code none}
 * (in any case) turns the advisor off: Jeorje then does not offer the {@code advisor} tool to any agent.
 */
public record ModelRoles(String defaultModel, String compactor, String advisor) {
    /** The advisor model when the config or the user names none. */
    public static final String DEFAULT_ADVISOR = "~deepseek/deepseek-pro-latest";
    /** The advisor value that turns the advisor off. */
    public static final String ADVISOR_OFF = "none";

    public ModelRoles {
        advisor = advisor == null || advisor.isBlank() ? DEFAULT_ADVISOR : advisor.strip();
        if (advisor.equalsIgnoreCase(ADVISOR_OFF)) advisor = ADVISOR_OFF;
    }

    public static ModelRoles from(JeorjeConfig.Models models) {
        return new ModelRoles(models.defaultModel(), models.compactor(), models.advisor());
    }

    private static boolean set(String s) { return s != null && !s.isBlank(); }

    public boolean compactorSet() { return set(compactor); }

    /** True while an advisor model is set, which is the default. */
    public boolean advisorSet() { return !ADVISOR_OFF.equals(advisor); }

    /** True when the {@code advisor} tool is offered to the agents. */
    public boolean advisorToolEnabled() { return advisorSet(); }

    /** The same roles with the advisor off. */
    public ModelRoles withAdvisorOff() { return new ModelRoles(defaultModel, compactor, ADVISOR_OFF); }

    /** The model that the pruner uses, or empty when the fallback pruner runs. */
    public java.util.Optional<String> pruningModel() {
        return compactorSet() ? java.util.Optional.of(compactor) : java.util.Optional.empty();
    }
}
