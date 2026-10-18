package org.github.lscoughlin.jeorje.core.agent;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The retry rules for model calls: network errors, HTTP 429, and HTTP 5xx are retried with exponential
 * backoff and jitter. Other HTTP 4xx errors are not retried.
 *
 * @param maxAttempts the largest number of calls (default 5)
 * @param baseDelay the delay before the first retry (default 1 s)
 * @param maxDelay the cap of the delay (default 30 s)
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts");
    }

    public static RetryPolicy defaults() { return new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(30)); }

    /** True if the error can go away on a new call. */
    public boolean isRetryable(Throwable error) {
        for (var t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            var status = statusOf(t);
            if (status >= 0) {
                return status == 0 || status == 429 || status >= 500;
            }
            if (t instanceof IOException) return true;
        }
        return false;
    }

    /** The delay before retry number {@code retry} (1 for the first retry). A Retry-After value replaces it. */
    public Duration delay(int retry, Throwable error) {
        var after = retryAfterOf(error);
        if (after != null) return after;
        var shift = Math.min(Math.max(retry - 1, 0), 30);
        var exp = baseDelay.multipliedBy(1L << shift);
        var capped = exp.compareTo(maxDelay) > 0 ? maxDelay : exp;
        var half = capped.toMillis() / 2;
        return Duration.ofMillis(half + (half <= 0 ? 0 : ThreadLocalRandom.current().nextLong(half + 1)));
    }

    /** Reads {@code int status()} from an error or its causes. Returns -1 when there is none. */
    private static int statusOf(Throwable t) {
        try {
            var m = t.getClass().getMethod("status");
            if (m.getReturnType() == int.class) return (int) m.invoke(t);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // no status method
        }
        return -1;
    }

    private static Duration retryAfterOf(Throwable error) {
        for (var t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            try {
                var m = t.getClass().getMethod("retryAfter");
                if (m.invoke(t) instanceof Duration d) return d;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // no retryAfter method
            }
        }
        return null;
    }
}
