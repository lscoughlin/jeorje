package org.github.lscoughlin.jeorje.core.model;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** A thread-safe cancellation flag with callbacks. */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    /** Returns a token that is never cancelled. */
    public static CancellationToken none() { return new CancellationToken(); }

    public boolean isCancelled() { return cancelled.get(); }

    /** Cancels the token and runs the callbacks once. Later calls do nothing. */
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (var callback : callbacks) callback.run();
        }
    }

    /** Registers a callback. It runs at once if the token is already cancelled. */
    public void onCancel(Runnable callback) {
        callbacks.add(callback);
        if (cancelled.get()) callback.run();
    }

    /** Throws {@link java.util.concurrent.CancellationException} if the token is cancelled. */
    public void throwIfCancelled() {
        if (cancelled.get()) throw new java.util.concurrent.CancellationException("cancelled");
    }
}
