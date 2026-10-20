package org.github.lscoughlin.jeorje.tools.mcp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig.McpServer;
import org.github.lscoughlin.jeorje.core.permissions.NetworkPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connects the configured MCP servers and holds their clients. A server that fails to connect is
 * skipped, so MCP never stops a session from starting. The manager closes all clients on
 * {@link #close()} and when the JVM exits.
 */
public final class McpManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(McpManager.class);
    private static final int MAX_NAME = 64;

    /** Makes the client for one server. */
    @FunctionalInterface
    public interface ClientFactory {
        McpClient create(String name, McpServer server) throws Exception;

        /** The standard factory: a stdio child process or an HTTP transport, from the config entry. */
        static ClientFactory standard(NetworkPolicy network, Path workingDirectory, Map<String, String> environment) {
            return (name, server) -> {
                var timeout = Duration.ofSeconds(server.timeoutSeconds());
                if (server.transport().equals("http")) {
                    var transport = new HttpTransport(name, server.url(), server.headers(), network,
                            environment::get, timeout);
                    return new McpClient(name, transport, timeout);
                }
                var command = new ArrayList<String>();
                command.add(server.command());
                command.addAll(server.args());
                var directory = server.cwd() == null || server.cwd().isBlank() ? workingDirectory : Path.of(server.cwd());
                var transport = new StdioTransport(name, command, directory,
                        StdioTransport.environmentFor(environment, server.envFrom(), server.env()));
                return new McpClient(name, transport, timeout);
            };
        }
    }

    /** The state of one server, for the {@code /mcp} command. */
    public record ServerStatus(String name, boolean connected, String detail, List<String> tools) {}

    private final Map<String, McpServer> servers;
    private final ClientFactory factory;
    private final Set<String> reserved;
    private final List<McpClient> clients = new CopyOnWriteArrayList<>();
    private final List<McpTool> tools = new CopyOnWriteArrayList<>();
    private final List<ServerStatus> statuses = new CopyOnWriteArrayList<>();
    private final Thread hook = new Thread(this::close, "mcp-shutdown");
    private volatile boolean closed;

    /**
     * @param servers  the configured servers by name
     * @param factory  makes a client for a server
     * @param reserved tool names that MCP tools must not take, such as the built-in tools
     */
    public McpManager(Map<String, McpServer> servers, ClientFactory factory, Set<String> reserved) {
        this.servers = servers;
        this.factory = factory;
        this.reserved = reserved;
    }

    /** Connects all enabled servers in parallel and collects their tools. */
    public void connect() {
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (IllegalStateException e) {
            log.atDebug().log(() -> "MCP shutdown hook not added: the JVM is stopping");
        }
        var results = new LinkedHashMap<String, Result>();
        var threads = new ArrayList<Thread>();
        servers.forEach((name, server) -> {
            if (!server.enabled()) {
                statuses.add(new ServerStatus(name, false, "disabled", List.of()));
                return;
            }
            var result = new Result();
            results.put(name, result);
            threads.add(Thread.ofVirtual().name("mcp-connect-" + name).start(() -> connectOne(name, server, result)));
        });
        for (var thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // Register in config order so that name collisions resolve the same way on every run.
        results.forEach((name, result) -> register(name, servers.get(name), result));
    }

    private static final class Result {
        McpClient client;
        List<McpClient.RemoteTool> remote = List.of();
        String error;
    }

    private void connectOne(String name, McpServer server, Result result) {
        McpClient client = null;
        try {
            client = factory.create(name, server);
            client.initialize();
            result.remote = client.listTools();
            result.client = client;
        } catch (Exception e) {
            result.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (client != null) client.close();
        }
    }

    private void register(String name, McpServer server, Result result) {
        if (result.client == null) {
            log.atWarn().log(() -> "MCP server " + name + " failed: " + result.error);
            statuses.add(new ServerStatus(name, false, "failed: " + result.error, List.of()));
            return;
        }
        clients.add(result.client);
        var timeout = Duration.ofSeconds(server.timeoutSeconds());
        var names = new ArrayList<String>();
        for (var remote : result.remote) {
            var toolName = toolName(name, remote.name());
            var taken = reserved.contains(toolName) || tools.stream().anyMatch(t -> t.definition().name().equals(toolName));
            if (taken) {
                log.atWarn().log(() -> "MCP tool skipped, the name is taken: " + toolName);
                continue;
            }
            tools.add(new McpTool(toolName, result.client, remote, timeout));
            names.add(toolName);
        }
        var stale = result.client.toolsStale() ? " (tool list changed)" : "";
        statuses.add(new ServerStatus(name, true, "connected" + stale, names));
    }

    /** {@code mcp__<server>__<tool>}, limited to letters, digits, {@code _} and {@code -}, and 64 characters. */
    static String toolName(String server, String tool) {
        var name = ("mcp__" + server + "__" + tool).replaceAll("[^A-Za-z0-9_-]", "_");
        return name.length() <= MAX_NAME ? name : name.substring(0, MAX_NAME);
    }

    /** The tools of all connected servers. */
    public List<McpTool> tools() {
        return List.copyOf(tools);
    }

    /** One status for each configured server. */
    public List<ServerStatus> status() {
        return List.copyOf(statuses);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        clients.forEach(McpClient::close);
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // The JVM is already stopping.
        }
    }
}
