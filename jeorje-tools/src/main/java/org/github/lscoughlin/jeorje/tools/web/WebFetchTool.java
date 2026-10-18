package org.github.lscoughlin.jeorje.tools.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.permissions.NetworkPolicy;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code webfetch} tool. It fetches an http or https URL and returns markdown text. It
 * follows up to five redirects and checks the domain policy again after each redirect.
 */
public final class WebFetchTool extends RecordTool<WebFetchTool.Input> {
    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);

    public static final int MAX_REDIRECTS = 5;
    public static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024;
    private static final String BLOCKED_PREFIX = "address is blocked";
    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*\"?([-\\w.:]+)", Pattern.CASE_INSENSITIVE);

    /** The input of {@code webfetch}. */
    public record Input(@Describe("The http or https URL to fetch.") String url) {}

    private final NetworkPolicy policy;
    private final Predicate<String> privateHostOk;
    private final long maxBytes;
    private final Duration timeout;
    private final HttpClient client;

    public WebFetchTool(NetworkPolicy policy, boolean allowPrivate, long maxBytes, Duration timeout) {
        this(policy, host -> allowPrivate, maxBytes, timeout);
    }

    /** The predicate tells whether the tool may fetch from a private address of the given host. */
    public WebFetchTool(NetworkPolicy policy, Predicate<String> privateHostOk, long maxBytes, Duration timeout) {
        super("webfetch", "Fetch an http or https URL. HTML is converted to markdown. "
                + "The tool follows up to five redirects and refuses private addresses.", Input.class, true);
        this.policy = policy;
        this.privateHostOk = privateHostOk;
        this.maxBytes = maxBytes;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout).build();
    }

    public WebFetchTool(NetworkPolicy policy, boolean allowPrivate) {
        this(policy, allowPrivate, DEFAULT_MAX_BYTES, Duration.ofSeconds(30));
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) throws Exception {
        if (input.url() == null || input.url().isBlank()) return ToolResultData.error("The url is required.");
        var current = input.url().trim();
        for (var hop = 0; hop <= MAX_REDIRECTS; hop++) {
            context.cancel().throwIfCancelled();
            var refusal = refusal(current);
            if (refusal != null) return ToolResultData.error("Refused: " + refusal);
            var target = current;
            log.atDebug().log(() -> "webfetch " + target);
            HttpResponse<InputStream> response;
            try {
                var request = HttpRequest.newBuilder(URI.create(current)).timeout(timeout)
                        .header("User-Agent", "jeorje-webfetch")
                        .header("Accept", "text/html,text/plain,application/json,*/*;q=0.5").GET().build();
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                return ToolResultData.error("Fetch failed: " + e.getMessage());
            }
            try (var body = response.body()) {
                var status = response.statusCode();
                if (status >= 300 && status < 400) {
                    var location = response.headers().firstValue("Location").orElse(null);
                    if (location == null) return ToolResultData.error("Redirect without a Location header.");
                    try {
                        current = URI.create(current).resolve(location.trim()).toString();
                    } catch (IllegalArgumentException e) {
                        return ToolResultData.error("Invalid redirect location: " + location);
                    }
                    continue;
                }
                if (status >= 400) return ToolResultData.error("HTTP status " + status + " for " + current);
                return read(response, body, current);
            }
        }
        return ToolResultData.error("Too many redirects (more than " + MAX_REDIRECTS + ").");
    }

    private String refusal(String url) {
        var verdict = policy.check(url);
        if (verdict.decision() != Decision.DENY) return null;
        if (verdict.reason().startsWith(BLOCKED_PREFIX)
                && privateHostOk.test(String.valueOf(NetworkPolicy.hostOf(url)))) {
            return null;
        }
        return verdict.reason();
    }

    private ToolResultData read(HttpResponse<InputStream> response, InputStream body, String url) throws IOException {
        var type = response.headers().firstValue("Content-Type").orElse("text/plain");
        var mime = type.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        var html = mime.equals("text/html") || mime.equals("application/xhtml+xml");
        var textual = html || mime.startsWith("text/") || mime.equals("application/json")
                || mime.endsWith("+json") || mime.endsWith("xml") || mime.equals("application/javascript");
        if (!textual) return ToolResultData.error("Unsupported content type: " + mime);
        var declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declared > maxBytes) return ToolResultData.error("Response too large: " + declared + " bytes. Limit is " + maxBytes + ".");
        var bytes = body.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
        if (bytes.length > maxBytes) return ToolResultData.error("Response too large. Limit is " + maxBytes + " bytes.");
        var text = new String(bytes, charset(type));
        return ToolResultData.ok(html ? HtmlToMarkdown.convert(text) : text);
    }

    private static Charset charset(String contentType) {
        var m = CHARSET.matcher(contentType);
        if (m.find()) {
            try {
                return Charset.forName(m.group(1));
            } catch (IllegalArgumentException e) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }
}
