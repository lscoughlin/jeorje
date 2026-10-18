package org.github.lscoughlin.jeorje.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.testkit.StubEmbedder;

/** Runs the command line with captured output. */
final class CliTestSupport {

    final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final CliEnvironment env;

    CliTestSupport(Path home, Path cwd, Map<String, String> vars, LlmClient llm) {
        var base = new CliEnvironment(home, cwd, vars, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8), null,
                config -> Optional.of(new StubEmbedder()), false);
        env = llm == null ? base : base.withLlmFactory((config, model, key) -> llm);
    }

    int run(String... args) {
        return JeorjeMain.execute(env, args);
    }

    String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }
}
