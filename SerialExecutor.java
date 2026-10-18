package org.github.lscoughlin.jeorje.swarm;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/** Runs tasks one at a time, in order, on virtual threads. */
final class SerialExecutor implements Executor {
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private boolean running;

    @Override
    public void execute(Runnable task) {
        synchronized (queue) {
            queue.add(task);
            if (running) {
                return;
            }
            running = true;
        }
        Thread.ofVirtual().start(this::drain);
    }

    private void drain() {
        while (true) {
            Runnable next;
            synchronized (queue) {
                next = queue.poll();
                if (next == null) {
                    running = false;
                    return;
                }
            }
            try {
                next.run();
            } catch (RuntimeException e) {
                // A failed task must not stop the queue.
            }
        }
    }
}
