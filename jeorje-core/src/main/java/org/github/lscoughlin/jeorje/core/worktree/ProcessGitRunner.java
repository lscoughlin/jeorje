package org.github.lscoughlin.jeorje.core.worktree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.config.SecretScrubber;
import org.github.lscoughlin.jeorje.core.permissions.ChildEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Starts the {@code git} program with a minimal environment and a timeout. */
final class ProcessGitRunner implements GitRunner {
    private static final Logger log = LoggerFactory.getLogger(ProcessGitRunner.class);
    private static final SecretScrubber SCRUBBER = new SecretScrubber();

    @Override
    public GitResult run(Path directory, List<String> args, Map<String, String> environment, Duration timeout) {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(args);
        var builder = new ProcessBuilder(command).directory(directory.toFile());
        var env = builder.environment();
        env.clear();
        env.putAll(ChildEnvironment.minimal());
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_EDITOR", "true");
        env.put("GIT_PAGER", "cat");
        env.put("LC_ALL", "C");
        env.putAll(environment);
        log.atDebug().log(() -> SCRUBBER.scrub("git " + String.join(" ", args) + " (in " + directory + ")"));
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new GitException("Cannot start git: " + e.getMessage(), e);
        }
        try {
            process.getOutputStream().close();
            var outReader = new Reader(process.getInputStream());
            var errReader = new Reader(process.getErrorStream());
            var outThread = Thread.ofVirtual().start(outReader);
            var errThread = Thread.ofVirtual().start(errReader);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new GitException("git " + args.get(0) + " timed out after " + timeout.toSeconds() + " s");
            }
            outThread.join();
            errThread.join();
            var result = new GitResult(process.exitValue(), outReader.text, errReader.text);
            log.atDebug().log(() -> SCRUBBER.scrub("git " + args.get(0) + " exit " + result.exit() + " "
                + abbreviate(result.text())));
            return result;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new GitException("Interrupted while git ran", e);
        } catch (IOException e) {
            throw new GitException("git failed: " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String text) {
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    private static final class Reader implements Runnable {
        private final java.io.InputStream stream;
        volatile String text = "";

        Reader(java.io.InputStream stream) { this.stream = stream; }

        @Override
        public void run() {
            try {
                text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                text = "";
            }
        }
    }
}
