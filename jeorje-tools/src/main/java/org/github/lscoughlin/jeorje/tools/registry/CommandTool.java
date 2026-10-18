package org.github.lscoughlin.jeorje.tools.registry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.permissions.ChildEnvironment;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A command tool from the config key {@code tools.extensions}. It has a name, a description, a
 * JSON Schema, and an executable. The executable reads the JSON input on stdin and writes the
 * result on stdout. A non-zero exit code makes an error result. The process gets a minimal
 * environment, so it never sees API keys.
 */
public final class CommandTool implements TimedTool {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ToolDefinition definition;
    private final List<String> command;
    private final boolean readOnly;
    private final Duration timeout;

    public CommandTool(String name, String description, JsonNode schema, List<String> command,
                       boolean readOnly, Duration timeout) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command tool " + name + " has no executable");
        }
        var effective = schema == null || schema.isNull() || schema.isMissingNode()
                ? JSON.createObjectNode().put("type", "object") : schema;
        this.definition = new ToolDefinition(name, description, effective);
        this.command = List.copyOf(command);
        this.readOnly = readOnly;
        this.timeout = timeout == null ? ToolRegistry.DEFAULT_TIMEOUT : timeout;
    }

    /**
     * Makes a command tool from one entry of {@code tools.extensions}. The keys are {@code name},
     * {@code description}, {@code schema} (or {@code inputSchema}), {@code command} (a list, or a
     * string that is split at spaces), {@code readOnly} (default false), and {@code timeoutSeconds}.
     */
    public static CommandTool fromConfig(Map<String, Object> entry) {
        var name = text(entry, "name");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("extension tool has no name");
        var description = text(entry, "description");
        var schemaValue = entry.containsKey("schema") ? entry.get("schema") : entry.get("inputSchema");
        var schema = schemaValue == null ? null : JSON.valueToTree(schemaValue);
        var raw = entry.get("command");
        var command = new ArrayList<String>();
        if (raw instanceof List<?> list) {
            list.forEach(o -> command.add(String.valueOf(o)));
        } else if (raw instanceof String line && !line.isBlank()) {
            command.addAll(List.of(line.trim().split("\\s+")));
        }
        var readOnly = entry.get("readOnly") instanceof Boolean b && b;
        var seconds = entry.get("timeoutSeconds") instanceof Number n ? n.longValue() : 0L;
        return new CommandTool(name, description == null ? "Command tool " + name : description, schema,
                command, readOnly, seconds > 0 ? Duration.ofSeconds(seconds) : null);
    }

    /** Makes the command tools of all entries. */
    public static List<CommandTool> fromConfig(List<Map<String, Object>> entries) {
        return entries.stream().map(CommandTool::fromConfig).toList();
    }

    private static String text(Map<String, Object> entry, String key) {
        var value = entry.get(key);
        return value == null ? null : value.toString();
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    @Override
    public ToolResultData execute(JsonNode input, ToolContext context) throws Exception {
        var builder = new ProcessBuilder(command).directory(context.workingDirectory().toFile());
        builder.environment().clear();
        builder.environment().putAll(ChildEnvironment.minimal());
        builder.redirectErrorStream(false);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return ToolResultData.error("Cannot start " + command.get(0) + ": " + e.getMessage());
        }
        try {
            var stderr = new java.util.concurrent.atomic.AtomicReference<>("");
            var errorReader = Thread.ofVirtual().start(() -> {
                try {
                    stderr.set(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // The process ended.
                }
            });
            // Write on a separate thread so a big input cannot block against a full stdout pipe.
            var payload = (input == null ? "{}" : JSON.writeValueAsString(input)).getBytes(StandardCharsets.UTF_8);
            Thread.ofVirtual().start(() -> {
                try (var in = process.getOutputStream()) {
                    in.write(payload);
                } catch (IOException ignored) {
                    // The program may close stdin early.
                }
            });
            var out = new java.util.concurrent.atomic.AtomicReference<>("");
            var outReader = Thread.ofVirtual().start(() -> {
                try {
                    out.set(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // The process ended.
                }
            });
            // waitFor can be interrupted (timeout or cancel). The finally block then kills the process.
            var code = process.waitFor();
            outReader.join();
            errorReader.join();
            var stdout = out.get();
            if (code != 0) {
                var detail = stdout.isBlank() ? stderr.get() : stdout;
                return ToolResultData.error("Command " + command.get(0) + " exited with code " + code
                        + (detail.isBlank() ? "" : ": " + detail.strip()));
            }
            return ToolResultData.ok(stdout);
        } finally {
            killTree(process);
        }
    }

    private static void killTree(Process process) {
        if (!process.isAlive()) return;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
