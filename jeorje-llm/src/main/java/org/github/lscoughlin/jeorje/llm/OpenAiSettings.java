package org.github.lscoughlin.jeorje.llm;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.github.lscoughlin.jeorje.core.config.ApiKey;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;

/**
 * Settings of one {@link OpenAiCompatibleClient}. The record never shows the API key in
 * {@code toString}.
 *
 * @param extraBody fields that the client adds to the top level of each request body
 */
public record OpenAiSettings(String baseUrl, String model, String apiKey, Map<String, String> headers,
                             int maxAttempts, Duration baseDelay, Duration maxDelay, Map<String, Object> extraBody) {

    public static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
    public static final String DEFAULT_MODEL = "~deepseek/deepseek-flash-latest";

    public OpenAiSettings {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(model, "model");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        extraBody = extraBody == null ? Map.of() : Map.copyOf(extraBody);
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
    }

    /** Settings with no extra body fields. */
    public OpenAiSettings(String baseUrl, String model, String apiKey, Map<String, String> headers,
                          int maxAttempts, Duration baseDelay, Duration maxDelay) {
        this(baseUrl, model, apiKey, headers, maxAttempts, baseDelay, maxDelay, Map.of());
    }

    /** Settings with the default retry policy: 5 attempts, 1 s base delay, 30 s cap. */
    public static OpenAiSettings of(String baseUrl, String model, String apiKey, Map<String, String> headers) {
        return new OpenAiSettings(baseUrl, model, apiKey, headers, 5, Duration.ofSeconds(1), Duration.ofSeconds(30));
    }

    /**
     * Settings from the config. The optional headers (HTTP-Referer, X-Title) come from llm.headers. The extra body
     * comes from llm.extraBody. For OpenRouter, when the config sets no {@code provider} field, the settings add
     * {@code provider.require_parameters: true}. OpenRouter then sends a request only to providers that support
     * all parameters of the request, such as tools, so that a provider without working tool calls is not used.
     */
    public static OpenAiSettings fromConfig(JeorjeConfig config, String model, ApiKey key) {
        var llm = config.llm();
        var base = llm.baseUrl() == null || llm.baseUrl().isBlank() ? DEFAULT_BASE_URL : llm.baseUrl();
        return of(base, model, key == null ? null : key.value(), llm.headers()).withExtraBody(effectiveExtraBody(base, llm.extraBody()));
    }

    /** The extra body fields that a request with this base URL gets. */
    public static Map<String, Object> effectiveExtraBody(String baseUrl, Map<String, Object> configured) {
        var out = new LinkedHashMap<String, Object>(configured);
        if (!out.containsKey("provider") && isOpenRouter(baseUrl)) {
            out.put("provider", Map.of("require_parameters", true));
        }
        return out;
    }

    private static boolean isOpenRouter(String baseUrl) {
        try {
            var host = URI.create(baseUrl).getHost();
            return host != null && (host.equals("openrouter.ai") || host.endsWith(".openrouter.ai"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public OpenAiSettings withRetry(int attempts, Duration base, Duration cap) {
        return new OpenAiSettings(baseUrl, model, apiKey, headers, attempts, base, cap, extraBody);
    }

    public OpenAiSettings withExtraBody(Map<String, Object> value) {
        return new OpenAiSettings(baseUrl, model, apiKey, headers, maxAttempts, baseDelay, maxDelay, value);
    }

    @Override
    public String toString() {
        return "OpenAiSettings[baseUrl=" + baseUrl + ", model=" + model + ", apiKey=****]";
    }
}
