package org.github.lscoughlin.jeorje.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.github.lscoughlin.jeorje.core.config.ApiKey;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.port.Embedder;
import org.github.lscoughlin.jeorje.core.port.LlmClient;

/**
 * Everything the commands take from the outside world. {@link #system()} is the real environment.
 * A test builds its own environment and sets the hooks to inject a scripted model client and a stub
 * embedder.
 *
 * @param home             the user home directory
 * @param cwd              the default working directory
 * @param env              the environment variables
 * @param out              standard output
 * @param err              standard error
 * @param llmFactory       the hook that makes a model client, or null for the real HTTP client
 * @param embedderFactory  the hook that makes the embedder, or null for the real ONNX embedder with fallback
 * @param configureLogging true to install the Logback appenders
 */
public record CliEnvironment(Path home, Path cwd, Map<String, String> env, PrintStream out, PrintStream err,
                             LlmFactory llmFactory, Function<JeorjeConfig, Optional<Embedder>> embedderFactory,
                             boolean configureLogging) {

    /** Makes a model client for one model name. */
    @FunctionalInterface
    public interface LlmFactory {
        LlmClient create(JeorjeConfig config, String model, ApiKey key);
    }

    public CliEnvironment {
        env = env == null ? Map.of() : Map.copyOf(env);
    }

    public static CliEnvironment system() {
        return new CliEnvironment(Path.of(System.getProperty("user.home")), Path.of(System.getProperty("user.dir")),
                System.getenv(), System.out, System.err, null, null, true);
    }

    public CliEnvironment withLlmFactory(LlmFactory value) {
        return new CliEnvironment(home, cwd, env, out, err, value, embedderFactory, configureLogging);
    }

    public CliEnvironment withEmbedderFactory(Function<JeorjeConfig, Optional<Embedder>> value) {
        return new CliEnvironment(home, cwd, env, out, err, llmFactory, value, configureLogging);
    }

    public CliEnvironment withLogging(boolean value) {
        return new CliEnvironment(home, cwd, env, out, err, llmFactory, embedderFactory, value);
    }
}
