package org.github.lscoughlin.jeorje.core.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;

/**
 * The values that one agent needs.
 *
 * @param sessionId the id of the session, used for chunk ids
 * @param workingDirectory the working directory of the tools
 * @param contextWindowTokens the context window of the model
 * @param budgetPercent the share of the window for the working context (default 60)
 * @param maxOutputTokens the output limit for each model call
 * @param recentTurns turns that stay verbatim (default 6)
 * @param stepLimit the largest number of model calls in one turn (default 200)
 * @param retry the retry settings for model calls
 * @param systemPrompt the {@code prompts.system} config, or null for the built-in text
 * @param instructions the text of the {@code AGENTS.md} files and the skill list, or an empty string
 * @param sleeper waits between retries
 * @param instructionsSource makes the instructions text for a working directory before each request, or null
 *        to use {@code instructions} as a fixed text
 * @param maxProtectedTokens the limit for protected skill results (default 20,000)
 */
public record AgentSettings(String sessionId, Path workingDirectory, int contextWindowTokens, int budgetPercent,
                            int maxOutputTokens, int recentTurns, int stepLimit, RetryPolicy retry,
                            JeorjeConfig.Prompt systemPrompt, String instructions, Sleeper sleeper,
                            InstructionsSource instructionsSource, int maxProtectedTokens, int recentMaxPercent) {

    /** The default limit for protected skill results, in tokens. */
    public static final int DEFAULT_MAX_PROTECTED_TOKENS = 20_000;
    /** The default share of the budget that the recent turns can use. */
    public static final int DEFAULT_RECENT_MAX_PERCENT = 40;

    /** The settings with the default share for the recent turns. */
    public AgentSettings(String sessionId, Path workingDirectory, int contextWindowTokens, int budgetPercent,
                         int maxOutputTokens, int recentTurns, int stepLimit, RetryPolicy retry,
                         JeorjeConfig.Prompt systemPrompt, String instructions, Sleeper sleeper,
                         InstructionsSource instructionsSource, int maxProtectedTokens) {
        this(sessionId, workingDirectory, contextWindowTokens, budgetPercent, maxOutputTokens, recentTurns,
            stepLimit, retry, systemPrompt, instructions, sleeper, instructionsSource, maxProtectedTokens,
            DEFAULT_RECENT_MAX_PERCENT);
    }

    /** Makes the instructions text of the system prompt. The agent calls it before each request. */
    @FunctionalInterface
    public interface InstructionsSource {
        String text(Path workingDirectory);
    }

    /** The settings without a dynamic instructions source. */
    public AgentSettings(String sessionId, Path workingDirectory, int contextWindowTokens, int budgetPercent,
                         int maxOutputTokens, int recentTurns, int stepLimit, RetryPolicy retry,
                         JeorjeConfig.Prompt systemPrompt, String instructions, Sleeper sleeper) {
        this(sessionId, workingDirectory, contextWindowTokens, budgetPercent, maxOutputTokens, recentTurns,
            stepLimit, retry, systemPrompt, instructions, sleeper, null, DEFAULT_MAX_PROTECTED_TOKENS);
    }

    /** The default step limit. The config key {@code agents.stepLimit} sets it. */
    public static final int DEFAULT_STEP_LIMIT = 200;

    public AgentSettings {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        if (contextWindowTokens <= 0) throw new IllegalArgumentException("contextWindowTokens");
        if (stepLimit <= 0) throw new IllegalArgumentException("stepLimit");
        retry = retry == null ? RetryPolicy.defaults() : retry;
        instructions = instructions == null ? "" : instructions;
        sleeper = sleeper == null ? Sleeper.system() : sleeper;
        if (maxProtectedTokens <= 0) maxProtectedTokens = DEFAULT_MAX_PROTECTED_TOKENS;
        if (recentMaxPercent <= 0 || recentMaxPercent > 100) recentMaxPercent = DEFAULT_RECENT_MAX_PERCENT;
    }

    /** The same settings with another share of the budget for the recent turns. */
    public AgentSettings withRecentMaxPercent(int percent) {
        return new AgentSettings(sessionId, workingDirectory, contextWindowTokens, budgetPercent, maxOutputTokens,
            recentTurns, stepLimit, retry, systemPrompt, instructions, sleeper, instructionsSource, maxProtectedTokens,
            percent);
    }

    /** The instructions text for the system prompt of an agent that works in a directory. */
    public String instructionsFor(Path directory) {
        if (instructionsSource == null) return instructions;
        var text = instructionsSource.text(directory);
        return text == null ? "" : text;
    }

    /** True when the instructions come from a source that the agent calls for each request. */
    public boolean hasInstructionsSource() {
        return instructionsSource != null;
    }

    /** The same settings with a dynamic instructions source. */
    public AgentSettings withInstructionsSource(InstructionsSource source) {
        return new AgentSettings(sessionId, workingDirectory, contextWindowTokens, budgetPercent, maxOutputTokens,
            recentTurns, stepLimit, retry, systemPrompt, instructions, sleeper, source, maxProtectedTokens,
            recentMaxPercent);
    }

    public AgentSettings withMaxProtectedTokens(int tokens) {
        return new AgentSettings(sessionId, workingDirectory, contextWindowTokens, budgetPercent, maxOutputTokens,
            recentTurns, stepLimit, retry, systemPrompt, instructions, sleeper, instructionsSource, tokens,
            recentMaxPercent);
    }

    /** Settings with the default values of the design. */
    public static AgentSettings defaults(String sessionId, Path workingDirectory, int contextWindowTokens) {
        return new AgentSettings(sessionId, workingDirectory, contextWindowTokens, 60, 8192, 6,
            DEFAULT_STEP_LIMIT, RetryPolicy.defaults(), null, "", Sleeper.system(), null, DEFAULT_MAX_PROTECTED_TOKENS);
    }

    public AgentSettings withStepLimit(int limit) {
        return new AgentSettings(sessionId, workingDirectory, contextWindowTokens, budgetPercent, maxOutputTokens,
            recentTurns, limit, retry, systemPrompt, instructions, sleeper, instructionsSource, maxProtectedTokens,
            recentMaxPercent);
    }

    public AgentSettings withSleeper(Sleeper next) {
        return new AgentSettings(sessionId, workingDirectory, contextWindowTokens, budgetPercent, maxOutputTokens,
            recentTurns, stepLimit, retry, systemPrompt, instructions, next, instructionsSource, maxProtectedTokens,
            recentMaxPercent);
    }

    public AgentSettings withSystemPrompt(JeorjeConfig.Prompt prompt) {
        return new AgentSettings(sessionId, workingDirectory, contextWindowTokens, budgetPercent, maxOutputTokens,
            recentTurns, stepLimit, retry, prompt, instructions, sleeper, instructionsSource, maxProtectedTokens,
            recentMaxPercent);
    }

    public AgentSettings withWindow(int window, int maxOutput) {
        return new AgentSettings(sessionId, workingDirectory, window, budgetPercent, maxOutput,
            recentTurns, stepLimit, retry, systemPrompt, instructions, sleeper, instructionsSource, maxProtectedTokens,
            recentMaxPercent);
    }

    /** Waits for a duration. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;

        static Sleeper system() { return d -> Thread.sleep(d); }
    }
}
