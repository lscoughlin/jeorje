package org.github.lscoughlin.jeorje.core.subagent;

import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.port.LlmClient;

/** Counts the model calls of one subagent task. A call over the limit does not reach the model. */
final class LimitedLlm implements LlmClient {
    private final LlmClient delegate;
    private final int limit;
    private final AtomicInteger calls = new AtomicInteger();
    private final Runnable onLimit;

    LimitedLlm(LlmClient delegate, int limit, Runnable onLimit) {
        this.delegate = delegate;
        this.limit = limit;
        this.onLimit = onLimit;
    }

    int calls() { return calls.get(); }

    @Override
    public Flow.Publisher<LlmEvent> stream(LlmRequest request, CancellationToken cancel) {
        if (calls.incrementAndGet() > limit) {
            onLimit.run();
            return subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) { }

                    @Override public void cancel() { }
                });
                subscriber.onComplete();
            };
        }
        return delegate.stream(request, cancel);
    }
}
