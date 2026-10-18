package org.github.lscoughlin.jeorje.core.worktree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Runs a verification command in a worktree. */
@FunctionalInterface
public interface Verifier {
    /** The result of one run. */
    record Result(boolean ok, String output) {}

    Result verify(Path directory, String command);

    /** Runs the command with {@code sh -c}. The timeout is 30 minutes. */
    static Verifier shell() {
        return (directory, command) -> {
            try {
                var process = new ProcessBuilder("sh", "-c", command).directory(directory.toFile())
                    .redirectErrorStream(true).start();
                process.getOutputStream().close();
                var buffer = new StringBuilder();
                var drain = Thread.ofVirtual().start(() -> {
                    try {
                        buffer.append(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        buffer.append(e.getMessage());
                    }
                });
                if (!process.waitFor(Duration.ofMinutes(30).toSeconds(), TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return new Result(false, "The verification command timed out.");
                }
                drain.join();
                var text = buffer.toString();
                if (text.length() > 8000) text = "..." + text.substring(text.length() - 8000);
                return new Result(process.exitValue() == 0, text);
            } catch (IOException e) {
                return new Result(false, "Cannot run the verification command: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(false, "Interrupted.");
            }
        };
    }
}
