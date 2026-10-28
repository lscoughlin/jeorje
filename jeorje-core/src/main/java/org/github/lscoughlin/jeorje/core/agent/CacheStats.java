package org.github.lscoughlin.jeorje.core.agent;

import org.github.lscoughlin.jeorje.core.model.LlmEvent;

/** Adds up the prompt cache numbers that the provider reports for the model calls of one agent. */
final class CacheStats {
    private int calls;
    private int lastInput;
    private int lastCached;
    private int lastWritten;
    private long totalInput;
    private long totalCached;
    private long totalWritten;

    synchronized void add(LlmEvent.Usage usage) {
        calls++;
        lastInput = usage.inputTokens();
        lastCached = usage.cachedTokens();
        lastWritten = usage.cacheWriteTokens();
        totalInput += lastInput;
        totalCached += lastCached;
        totalWritten += lastWritten;
    }

    synchronized ContextReport.Cache snapshot() {
        return new ContextReport.Cache(calls, lastInput, lastCached, lastWritten, totalInput, totalCached,
            totalWritten);
    }
}
