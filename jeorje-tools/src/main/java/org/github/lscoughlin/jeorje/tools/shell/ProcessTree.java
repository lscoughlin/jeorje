package org.github.lscoughlin.jeorje.tools.shell;

import java.util.LinkedHashSet;
import java.util.concurrent.TimeUnit;

/** Kills a process and all its descendants. */
public final class ProcessTree {
    private ProcessTree() {}

    /** Kills the descendants first, then the process. It repeats to catch new children. */
    public static void kill(Process process) {
        var handle = process.toHandle();
        var seen = new LinkedHashSet<ProcessHandle>();
        for (var round = 0; round < 3; round++) {
            var found = handle.descendants().filter(seen::add).toList();
            handle.destroyForcibly();
            for (var child : found) child.destroyForcibly();
            if (found.isEmpty() && round > 0) break;
        }
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (var child : seen) {
            child.destroyForcibly();
        }
    }
}
