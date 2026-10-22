package org.github.lscoughlin.jeorje.tools.shell;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one shell command to its end. It uses the same process code as the {@code bash} tool but does not check
 * a permission policy. The caller decides whether the command may run.
 */
public final class ShellRunner {
    private static final Logger log = LoggerFactory.getLogger(ShellRunner.class);
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private ShellRunner() {}

    /**
     * Runs a command and waits for it.
     *
     * @param directory the working directory
     * @param command the command text
     * @param settings the shell settings
     * @param timeout the longest time to wait. The runner kills the process tree after this time.
     * @return the result. It never throws for a command that cannot start.
     */
    public static ShellResult run(Path directory, String command, ShellSettings settings, Duration timeout) {
        var id = "bang-" + COUNTER.incrementAndGet();
        ShellProcess process;
        try {
            process = ShellProcess.start(id, command, directory, settings);
        } catch (IOException e) {
            log.atWarn().log(() -> "Cannot start the command " + command + ": " + e.getMessage());
            return new ShellResult(command, "[cannot start the command: " + e.getMessage() + "]", -1, false);
        }
        ProcessRegistry.register(process, directory);
        try {
            var deadline = System.nanoTime() + timeout.toNanos();
            try {
                while (!process.await(50)) {
                    if (System.nanoTime() >= deadline) {
                        process.kill();
                        return finish(command, process, -1, true,
                                "[timed out after " + timeout.toSeconds() + " s. The process tree was killed.]");
                    }
                }
            } catch (InterruptedException e) {
                process.kill();
                Thread.currentThread().interrupt();
                return finish(command, process, -1, false, "[interrupted. The process tree was killed.]");
            }
            return finish(command, process, process.exitCode(), false, null);
        } finally {
            ProcessRegistry.unregister(process);
        }
    }

    private static ShellResult finish(String command, ShellProcess process, int code, boolean timedOut,
                                      String footer) {
        process.finishOutput();
        var text = process.buffer().render();
        if (footer != null) {
            text = text.isEmpty() ? footer : text.endsWith("\n") ? text + footer : text + "\n" + footer;
        }
        return new ShellResult(command, text, code, timedOut);
    }
}
