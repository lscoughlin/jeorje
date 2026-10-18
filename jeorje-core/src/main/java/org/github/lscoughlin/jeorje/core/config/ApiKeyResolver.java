package org.github.lscoughlin.jeorje.core.config;

import java.util.Map;
import java.util.Optional;

/** Reads the API key from the environment variable that {@code llm.apiKeyEnv} names. */
public final class ApiKeyResolver {

    public static final String DEFAULT_ENV = "OPENROUTER_API_KEY";

    private final Map<String, String> env;

    public ApiKeyResolver(Map<String, String> env) {
        this.env = Map.copyOf(env);
    }

    public static ApiKeyResolver system() {
        return new ApiKeyResolver(System.getenv());
    }

    /** The first named variable that has a non-blank value. */
    public Optional<ApiKey> resolve(JeorjeConfig config) {
        var names = config.llm().apiKeyEnv();
        return resolve(names.isEmpty() ? java.util.List.of(DEFAULT_ENV) : names);
    }

    public Optional<ApiKey> resolve(java.util.List<String> names) {
        for (var name : names) {
            var value = env.get(name);
            if (value != null && !value.isBlank()) {
                return Optional.of(new ApiKey(name, value.strip()));
            }
        }
        return Optional.empty();
    }

    /** A scrubber that knows the API key and the swarm token of this config. */
    public SecretScrubber scrubber(JeorjeConfig config) {
        var scrubber = new SecretScrubber();
        resolve(config).ifPresent(k -> scrubber.register(k.value()));
        config.llm().apiKeyEnv().forEach(n -> scrubber.register(env.get(n)));
        scrubber.register(config.swarm().token());
        scrubber.register(env.get("JEORJE_SWARM_TOKEN"));
        scrubber.register(env.get("OPENAI_API_KEY"));
        return scrubber;
    }
}
