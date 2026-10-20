package org.github.lscoughlin.jeorje.tools.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.core.permissions.ChildEnvironment;
import org.github.lscoughlin.jeorje.tools.shell.ProcessTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs an MCP server as a child process. Messages are single lines of JSON on stdin and stdout.
 * The child gets a minimal environment plus the variables that the config names. Stderr goes to
 * the log.
 */
public final class StdioTransport implements McpTransport {
    private static final Logger log = LoggerFactory.getLogger(StdioTransport.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String name;
    private final List<String> command;
    private final Path directory;
    private final Map<String, String> environment;
    private Consumer<JsonNode> messageHandler = m -> { };
    private Consumer<Throwable> closeHandler = t -> { };
    private final AtomicBoolean closed = new AtomicBoolean();
    private Process process;
    private BufferedWriter stdin;

    /**
     * @param name        the server name, for logs
     * @param command     the executable and its arguments
     * @param directory   the working directory of the child
     * @param environment the full child environment; build it with {@link #environmentFor}
     */
    public StdioTransport(String name, List<String> command, Path directory, Map<String, String> environment) {
        this.name = name;
        this.command = List.copyOf(command);
        this.directory = directory;
        this.environment = Map.copyOf(environment);
    }

    /** The minimal environment, then the named variables of the parent, then the literal values. */
    public static Map<String, String> environmentFor(Map<String, String> parent, List<String> envFrom,
                                                     Map<String, String> literal) {
        var out = new java.util.LinkedHashMap<>(ChildEnvironment.minimal(parent));
        for (var variable : envFrom) {
            var value = parent.get(variable);
            if (value == null) {
                log.atWarn().log(() -> "MCP env variable is not set: " + variable);
            } else {
                out.put(variable, value);
            }
        }
        out.putAll(literal);
        return out;
    }

    @Override
    public void onMessage(Consumer<JsonNode> handler) {
        this.messageHandler = handler;
    }

    @Override
    public void onClose(Consumer<Throwable> handler) {
        this.closeHandler = handler;
    }

    @Override
    public void start() throws IOException {
        var builder = new ProcessBuilder(command);
        if (directory != null) builder.directory(directory.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        process = builder.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        var out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        var err = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
        Thread.ofVirtual().name("mcp-" + name + "-out").start(() -> pump(out));
        Thread.ofVirtual().name("mcp-" + name + "-err").start(() -> drain(err));
    }

    private void pump(BufferedReader out) {
        Throwable end = null;
        try {
            String line;
            while ((line = out.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    messageHandler.accept(JSON.readTree(line));
                } catch (RuntimeException e) {
                    log.atWarn().log(() -> "MCP " + name + " sent a bad message: " + e.getMessage());
                }
            }
            end = new IOException("MCP server " + name + " closed its output");
        } catch (IOException e) {
            end = e;
        } finally {
            if (closed.compareAndSet(false, true)) {
                closeHandler.accept(end);
                kill();
            }
        }
    }

    private void drain(BufferedReader err) {
        try {
            String line;
            while ((line = err.readLine()) != null) {
                var text = line;
                log.atDebug().log(() -> "mcp " + name + ": " + text);
            }
        } catch (IOException e) {
            // The process ended.
        }
    }

    @Override
    public CompletableFuture<Void> send(JsonNode message) {
        var future = new CompletableFuture<Void>();
        try {
            var text = JSON.writeValueAsString(message);
            synchronized (this) {
                stdin.write(text);
                stdin.write('\n');
                stdin.flush();
            }
            future.complete(null);
        } catch (IOException | RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeHandler.accept(new IOException("MCP client closed"));
        }
        kill();
    }

    private void kill() {
        var p = process;
        if (p == null) return;
        try {
            stdin.close();
        } catch (IOException e) {
            // The pipe is already closed.
        }
        try {
            if (!p.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)) ProcessTree.kill(p);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ProcessTree.kill(p);
        }
    }

    /** The command line, for status output. */
    public List<String> command() {
        return new ArrayList<>(command);
    }
}
