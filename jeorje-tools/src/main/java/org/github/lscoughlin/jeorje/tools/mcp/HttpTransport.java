package org.github.lscoughlin.jeorje.tools.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.permissions.NetworkPolicy;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Talks to an MCP server over Streamable HTTP. Each message is a POST. The reply is one JSON
 * body or an event stream. The transport never follows redirects. The network policy checks the
 * URL before every request. The config names the URL, so a new domain is not a reason to stop.
 * A denied URL is.
 */
public final class HttpTransport implements McpTransport {
    private static final Logger log = LoggerFactory.getLogger(HttpTransport.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private final String name;
    private final URI uri;
    private final Map<String, String> headers;
    private final NetworkPolicy policy;
    private final HttpClient client;
    private final Duration timeout;
    private final AtomicReference<String> session = new AtomicReference<>();
    private volatile String version;
    private volatile boolean closed;
    private Consumer<JsonNode> messageHandler = m -> { };
    private Consumer<Throwable> closeHandler = t -> { };

    /**
     * @param headers the configured headers; {@code ${NAME}} in a value is replaced from {@code environment}
     */
    public HttpTransport(String name, String url, Map<String, String> headers, NetworkPolicy policy,
                         Function<String, String> environment, Duration timeout) {
        this.name = name;
        this.uri = URI.create(url);
        this.headers = expand(headers, environment);
        this.policy = policy;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Replaces {@code ${NAME}} with the environment variable. A missing variable is an error. */
    static Map<String, String> expand(Map<String, String> headers, Function<String, String> environment) {
        var out = new LinkedHashMap<String, String>();
        headers.forEach((key, value) -> {
            var matcher = VARIABLE.matcher(value);
            var result = new StringBuilder();
            while (matcher.find()) {
                var replacement = environment.apply(matcher.group(1));
                if (replacement == null) {
                    throw new IllegalArgumentException("Environment variable " + matcher.group(1)
                            + " is not set (header " + key + ")");
                }
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(result);
            out.put(key, result.toString());
        });
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
        var verdict = policy.check(uri.toString());
        if (verdict.decision() == Decision.DENY) {
            throw new IOException("MCP server " + name + " is blocked: " + verdict.reason());
        }
    }

    @Override
    public void protocolVersion(String version) {
        this.version = version;
    }

    @Override
    public CompletableFuture<Void> send(JsonNode message) {
        var future = new CompletableFuture<Void>();
        Thread.ofVirtual().name("mcp-" + name + "-post").start(() -> {
            try {
                post(message);
                future.complete(null);
            } catch (IOException | RuntimeException e) {
                future.completeExceptionally(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private void post(JsonNode message) throws IOException, InterruptedException {
        var verdict = policy.check(uri.toString());
        if (verdict.decision() == Decision.DENY) {
            throw new IOException("MCP server " + name + " is blocked: " + verdict.reason());
        }
        var builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        headers.forEach(builder::header);
        if (session.get() != null) builder.header("Mcp-Session-Id", session.get());
        if (version != null) builder.header("MCP-Protocol-Version", version);
        builder.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(message)));
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            response.headers().firstValue("Mcp-Session-Id").ifPresent(session::set);
            var status = response.statusCode();
            if (status == 202 || status == 204) return;
            if (status / 100 != 2) {
                var text = new String(body.readNBytes(2000), StandardCharsets.UTF_8);
                throw new IOException("MCP server " + name + " answered HTTP " + status + ": " + text);
            }
            var type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT);
            if (type.startsWith("text/event-stream")) {
                readEvents(new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8)));
            } else {
                deliver(new String(body.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private void readEvents(BufferedReader reader) throws IOException {
        var data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (!data.isEmpty()) deliver(data.toString());
                data.setLength(0);
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                var value = line.substring(5);
                data.append(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        if (!data.isEmpty()) deliver(data.toString());
    }

    private void deliver(String text) {
        if (text.isBlank()) return;
        try {
            var node = JSON.readTree(text);
            if (node.isArray()) {
                node.forEach(messageHandler);
            } else {
                messageHandler.accept(node);
            }
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "MCP " + name + " sent a bad message: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        var id = session.get();
        if (id != null) {
            try {
                var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).DELETE()
                        .header("Mcp-Session-Id", id);
                headers.forEach(request::header);
                client.send(request.build(), HttpResponse.BodyHandlers.discarding());
            } catch (IOException | RuntimeException e) {
                log.atDebug().log(() -> "MCP " + name + " session end failed: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeHandler.accept(new IOException("MCP client closed"));
    }
}
