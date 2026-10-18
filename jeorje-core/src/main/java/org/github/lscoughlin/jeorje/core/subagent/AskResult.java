package org.github.lscoughlin.jeorje.core.subagent;

/**
 * The result of {@code agent_ask}.
 *
 * @param answer the answer text, or null on a failure
 * @param reason the reason of a failure, or null
 * @param summary the completion summary of an ended target, or null
 */
public record AskResult(String answer, String reason, String summary) {
    public static final String TIMEOUT = "timeout";
    public static final String DEADLOCK = "deadlock";
    public static final String BUSY = "busy";
    public static final String NOT_STARTED = "not started";
    public static final String ENDED = "ended";
    public static final String LIMIT = "limit";
    public static final String CANCELLED = "cancelled";
    public static final String UNKNOWN = "unknown agent";
    public static final String OPEN_ASK = "open ask";

    public boolean ok() { return reason == null; }

    public static AskResult answer(String text) { return new AskResult(text, null, null); }

    public static AskResult failure(String reason) { return new AskResult(null, reason, null); }

    public static AskResult ended(String summary) { return new AskResult(null, ENDED, summary); }
}
