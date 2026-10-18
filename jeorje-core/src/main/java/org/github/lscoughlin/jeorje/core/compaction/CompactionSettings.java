package org.github.lscoughlin.jeorje.core.compaction;

import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;

/**
 * The policy values.
 *
 * @param budgetTokens the token budget of the working context
 * @param recentTurns turns that stay verbatim (default 6)
 * @param aggressiveDropAfterTurns extra age, after the recent turns, at which an AGGRESSIVE chunk goes to level 3
 * @param protectedRecentTurns the number of turns in which a read, written, or edited file path is immediate
 */
public record CompactionSettings(int budgetTokens, int softPercent, int hardPercent, int recentTurns,
                                 int aggressiveDropAfterTurns, int batchChunks, int batchTokens,
                                 int protectedRecentTurns) {
    /** The default number of turns for immediate file paths. */
    public static final int DEFAULT_PROTECTED_RECENT_TURNS = 6;

    /** The settings with the default number of turns for immediate file paths. */
    public CompactionSettings(int budgetTokens, int softPercent, int hardPercent, int recentTurns,
                              int aggressiveDropAfterTurns, int batchChunks, int batchTokens) {
        this(budgetTokens, softPercent, hardPercent, recentTurns, aggressiveDropAfterTurns, batchChunks,
            batchTokens, DEFAULT_PROTECTED_RECENT_TURNS);
    }

    public static CompactionSettings defaults(int budgetTokens) {
        return new CompactionSettings(budgetTokens, 50, 80, 6, 12, 20, 16_000, DEFAULT_PROTECTED_RECENT_TURNS);
    }

    public static CompactionSettings from(JeorjeConfig.Context ctx, int contextWindowTokens) {
        var c = ctx.compaction();
        return new CompactionSettings((int) ((long) contextWindowTokens * ctx.budgetPercent() / 100),
            c.softPercent(), c.hardPercent(), ctx.recentTurns(), c.aggressiveDropAfterTurns(),
            c.batchChunks(), c.batchTokens(), c.protectedRecentTurns() > 0 ? c.protectedRecentTurns()
                : DEFAULT_PROTECTED_RECENT_TURNS);
    }

    public long softLimit() { return (long) budgetTokens * softPercent / 100; }

    public long hardLimit() { return (long) budgetTokens * hardPercent / 100; }
}
