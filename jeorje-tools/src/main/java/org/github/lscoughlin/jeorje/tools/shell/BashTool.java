package org.github.lscoughlin.jeorje.tools.shell;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Allowed;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.github.lscoughlin.jeorje.tools.registry.TimedTool;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code bash} tool. It runs a command with the configured shell in the working directory. The
 * environment is minimal. On timeout and on cancellation, the tool kills the whole process tree.
 */
public final class BashTool extends RecordTool<BashTool.Input> implements TimedTool {
    /** The default timeout in seconds. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;
    /** The longest timeout in seconds. */
    public static final int MAX_TIMEOUT_SECONDS = 600;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The input of {@code bash}. */
    public record Input(
            @Describe("The command to run. Required for the operation run.") String command,
            @Allowed({"run", "poll", "kill"})
            @Describe("run (default) starts a command. poll shows new output of a background process. "
                    + "kill stops a background process.") String operation,
            @Describe("Timeout in seconds. Default 120. Maximum 600.") Integer timeout,
            @Describe("True to run the command in the background. The result has a process id.") Boolean background,
            @Describe("The process id, for the operations poll and kill.") String id) {}

    private final PermissionPolicy policy;
    private final ShellSettings settings;
    private final int defaultTimeoutSeconds;
    private final int maxTimeoutSeconds;
    private final Map<String, ShellProcess> background = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    public BashTool(PermissionPolicy policy) {
        this(policy, ShellSettings.defaults());
    }

    public BashTool(PermissionPolicy policy, ShellSettings settings) {
        this(policy, settings, DEFAULT_TIMEOUT_SECONDS);
    }

    /** @param defaultTimeoutSeconds the timeout when the call gives none ({@code tools.bash.timeoutSeconds}) */
    public BashTool(PermissionPolicy policy, ShellSettings settings, int defaultTimeoutSeconds) {
        super("bash", "Run a shell command in the working directory. The environment is minimal and has no "
                + "API keys. Output is shortened: the start and the end stay. With background=true the command "
                + "runs in the background and the result has a process id. Use operation poll or kill with that id.",
                Input.class, false);
        this.policy = policy;
        this.settings = settings;
        this.defaultTimeoutSeconds = Math.max(1, defaultTimeoutSeconds);
        this.maxTimeoutSeconds = Math.max(MAX_TIMEOUT_SECONDS, this.defaultTimeoutSeconds);
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(maxTimeoutSeconds + 30L);
    }

    /** Kills all background processes. */
    public void close() {
        background.values().forEach(p -> {
            p.kill();
            ProcessRegistry.unregister(p);
        });
        background.clear();
    }

    /**
     * Kills the shell processes of all bash tools that run in a directory or below it. A worktree cleanup calls
     * this before it removes the worktree.
     *
     * @return the number of processes that it killed
     */
    public static int stopProcessesIn(java.nio.file.Path directory) {
        return ProcessRegistry.stopIn(directory);
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) throws Exception {
        var operation = input.operation() == null ? "run" : input.operation();
        return switch (operation) {
            case "poll" -> poll(input);
            case "kill" -> kill(input);
            default -> start(input, context);
        };
    }

    private ToolResultData start(Input input, ToolContext context) throws IOException {
        var command = input.command();
        if (command == null || command.isBlank()) return ToolResultData.error("The command is empty.");
        if (policy != null) {
            var node = JSON.createObjectNode().put("command", command);
            if (policy.check(new ToolUse("bash-check", "bash", node)) == PermissionPolicy.Decision.DENY) {
                return ToolResultData.error("Refused: the permission policy does not allow this command.");
            }
        }
        var seconds = input.timeout() == null ? defaultTimeoutSeconds
                : Math.max(1, Math.min(input.timeout(), maxTimeoutSeconds));
        var runInBackground = Boolean.TRUE.equals(input.background());
        var id = runInBackground ? "bg-" + counter.incrementAndGet() : "fg-" + counter.incrementAndGet();
        var process = ShellProcess.start(id, command, context.workingDirectory(), settings);
        ProcessRegistry.register(process, context.workingDirectory());
        if (runInBackground) {
            background.put(id, process);
            return ToolResultData.ok("Started background process " + id + ". Use operation poll or kill with id " + id + ".");
        }
        try {
            return runForeground(process, seconds, context);
        } finally {
            ProcessRegistry.unregister(process);
        }
    }

    private ToolResultData runForeground(ShellProcess process, int seconds, ToolContext context) {
        var deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        context.cancel().onCancel(() -> {
            if (process.isAlive()) process.kill();
        });
        try {
            while (true) {
                if (process.await(50)) break;
                if (context.cancel().isCancelled()) {
                    process.kill();
                    return finish(process, "Command cancelled. The process tree was killed.", true);
                }
                if (System.nanoTime() >= deadline) {
                    process.kill();
                    return finish(process, "Command timed out after " + seconds
                            + " s. The process tree was killed.", true);
                }
            }
        } catch (InterruptedException e) {
            process.kill();
            Thread.currentThread().interrupt();
            return finish(process, "Command cancelled. The process tree was killed.", true);
        }
        process.finishOutput();
        if (context.cancel().isCancelled()) {
            return finish(process, "Command cancelled. The process tree was killed.", true);
        }
        var code = process.exitCode();
        var text = process.buffer().render();
        var footer = code == 0 ? "[exit code 0]" : "[exit code " + code + "]";
        return new ToolResultData(join(text, footer), code != 0);
    }

    private static ToolResultData finish(ShellProcess process, String note, boolean error) {
        return new ToolResultData(join(process.buffer().render(), "[" + note + "]"), error);
    }

    private static String join(String text, String footer) {
        return text.isEmpty() ? footer : text.endsWith("\n") ? text + footer : text + "\n" + footer;
    }

    private ToolResultData poll(Input input) {
        var process = lookup(input);
        if (process == null) return unknown(input);
        if (process.isAlive()) {
            var text = process.buffer().drain();
            return ToolResultData.ok(join(text, "[process " + process.id() + " is running]"));
        }
        process.finishOutput();
        var text = process.buffer().drain();
        background.remove(process.id());
        ProcessRegistry.unregister(process);
        var code = process.exitCode();
        return ToolResultData.ok(join(text, "[process " + process.id() + " exited with code " + code + "]"));
    }

    private ToolResultData kill(Input input) {
        var process = lookup(input);
        if (process == null) return unknown(input);
        var wasAlive = process.isAlive();
        process.kill();
        var text = process.buffer().drain();
        background.remove(process.id());
        ProcessRegistry.unregister(process);
        var note = wasAlive ? "process " + process.id() + " was killed with its process tree"
                : "process " + process.id() + " had already exited with code " + process.exitCode();
        return ToolResultData.ok(join(text, "[" + note + "]"));
    }

    private ShellProcess lookup(Input input) {
        return input.id() == null ? null : background.get(input.id());
    }

    private static ToolResultData unknown(Input input) {
        return ToolResultData.error(input.id() == null ? "The id is missing."
                : "Unknown background process id '" + input.id() + "'.");
    }
}
