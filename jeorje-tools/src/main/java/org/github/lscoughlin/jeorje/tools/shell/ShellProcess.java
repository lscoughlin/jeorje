package org.github.lscoughlin.jeorje.tools.shell;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.permissions.ChildEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One running shell process. Output (stdout and stderr together) goes to a buffer and a listener. */
final class ShellProcess {
    private static final Logger log = LoggerFactory.getLogger(ShellProcess.class);

    private final String id;
    private final Process process;
    private final OutputBuffer buffer;
    private final Thread reader;

    private ShellProcess(String id, Process process, OutputBuffer buffer, OutputListener listener) {
        this.id = id;
        this.process = process;
        this.buffer = buffer;
        this.reader = Thread.ofVirtual().name("bash-output-" + id).unstarted(() -> pump(listener));
    }

    static ShellProcess start(String id, String command, Path directory, ShellSettings settings) throws IOException {
        var args = new ArrayList<>(settings.shell());
        args.add(command);
        var builder = new ProcessBuilder(args).directory(directory.toFile()).redirectErrorStream(true);
        builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        var environment = builder.environment();
        environment.clear();
        environment.putAll(ChildEnvironment.minimal(settings.sourceEnvironment()));
        var process = builder.start();
        var shellProcess = new ShellProcess(id, process, new OutputBuffer(settings.headChars(), settings.tailChars()),
                settings.listener());
        shellProcess.reader.start();
        return shellProcess;
    }

    private void pump(OutputListener listener) {
        try (var in = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            var chars = new char[4096];
            int n;
            while ((n = in.read(chars)) >= 0) {
                var text = new String(chars, 0, n);
                buffer.append(text);
                try {
                    listener.onOutput(id, text);
                } catch (RuntimeException e) {
                    log.atWarn().log(() -> "output listener failed: " + e);
                }
            }
        } catch (IOException e) {
            log.atDebug().log(() -> "output stream of " + id + " closed: " + e);
        }
    }

    String id() {
        return id;
    }

    boolean isAlive() {
        return process.isAlive();
    }

    int exitCode() {
        return process.exitValue();
    }

    /** Waits up to the given time. Returns true if the process ended. */
    boolean await(long millis) throws InterruptedException {
        return process.waitFor(millis, TimeUnit.MILLISECONDS);
    }

    /** Waits a short time for the reader to take the last output. */
    void finishOutput() {
        try {
            reader.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void kill() {
        ProcessTree.kill(process);
        finishOutput();
    }

    OutputBuffer buffer() {
        return buffer;
    }
}
