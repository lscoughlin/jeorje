package org.github.lscoughlin.jeorje.llm;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.github.lscoughlin.jeorje.core.config.ApiKey;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;

/**
 * Settings of one {@link OpenAiCompatibleClient}. The record never shows the API key in
 * {@code toString}.
 */
public record OpenAiSettings(String baseUrl, String model, String apiKey, Map<String, String> headers,
                             int maxAttempts, Duration baseDelay, Duration maxDelay) {

    public static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
    public static final String DEFAULT_MODEL = "~deepseek/deepseek-flash-latest";

    public OpenAiSettings {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(model, "model");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
    }

    /** Settings with the default retry policy: 5 attempts, 1 s base delay, 30 s cap. */
    public static OpenAiSettings of(String baseUrl, String model, String apiKey, Map<String, String> headers) {
        return new OpenAiSettings(baseUrl, model, apiKey, headers, 5, Duration.ofSeconds(1), Duration.ofSeconds(30));
    }

    /** Settings from the config. The optional headers (HTTP-Referer, X-Title) come from llm.headers. */
    public static OpenAiSettings fromConfig(JeorjeConfig config, String model, ApiKey key) {
        var llm = config.llm();
        var base = llm.baseUrl() == null || llm.baseUrl().isBlank() ? DEFAULT_BASE_URL : llm.baseUrl();
        return of(base, model, key == null ? null : key.value(), llm.headers());
    }

    public OpenAiSettings withRetry(int attempts, Duration base, Duration cap) {
        return new OpenAiSettings(baseUrl, model, apiKey, headers, attempts, base, cap);
    }

    @Override
    public String toString() {
        return "OpenAiSettings[baseUrl=" + baseUrl + ", model=" + model + ", apiKey=****]";
    }
}
