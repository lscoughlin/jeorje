package org.github.lscoughlin.jeorje.tools.shell;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shell processes that run now, by working directory. All {@link BashTool} instances of the JVM share it. A
 * worktree cleanup uses it to stop the processes that run in the worktree.
 */
final class ProcessRegistry {
    private static final Map<ShellProcess, Path> RUNNING = new ConcurrentHashMap<>();

    private ProcessRegistry() {}

    static void register(ShellProcess process, Path directory) {
        RUNNING.put(process, directory.toAbsolutePath().normalize());
    }

    static void unregister(ShellProcess process) {
        RUNNING.remove(process);
    }

    /** Kills every live process whose working directory is the directory or is below it. Returns the count. */
    static int stopIn(Path directory) {
        var root = directory.toAbsolutePath().normalize();
        var stopped = 0;
        for (var entry : RUNNING.entrySet()) {
            var process = entry.getKey();
            if (!process.isAlive()) {
                RUNNING.remove(process);
                continue;
            }
            if (entry.getValue().startsWith(root)) {
                process.kill();
                RUNNING.remove(process);
                stopped++;
            }
        }
        return stopped;
    }
}
