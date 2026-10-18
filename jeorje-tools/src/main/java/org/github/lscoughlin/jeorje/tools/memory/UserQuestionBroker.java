package org.github.lscoughlin.jeorje.tools.memory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;

/**
 * Carries questions from tools to the user and answers back. The UI sets the listener and calls
 * {@link #answer}. {@link #ask} blocks until the answer arrives or the token is cancelled.
 */
public final class UserQuestionBroker {
    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong();
    private final Consumer<UserQuestion> listener;

    public UserQuestionBroker(Consumer<UserQuestion> listener) { this.listener = listener; }

    /** Sends the question event and waits for the answer. */
    public String ask(AgentId agent, String question, List<String> choices, CancellationToken cancel)
            throws Exception {
        var id = "q" + counter.incrementAndGet();
        var future = new CompletableFuture<String>();
        pending.put(id, future);
        cancel.onCancel(() -> future.completeExceptionally(new CancellationException("cancelled")));
        try {
            listener.accept(new UserQuestion(id, agent, question, choices));
            return future.get();
        } finally {
            pending.remove(id);
        }
    }

    /** Gives the answer to a question. Returns false if no such question waits. */
    public boolean answer(String questionId, String answer) {
        var future = pending.get(questionId);
        return future != null && future.complete(answer);
    }

    public int pendingCount() { return pending.size(); }
}
