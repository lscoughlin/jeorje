package org.github.lscoughlin.jeorje.cli.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs all scenarios of scripts/e2e through run.py against the shaded jar. It runs only when JEORJE_PTY=1, Python 3 is available,
 * and the jar exists.
 */
@Tag("pty")
@EnabledIfEnvironmentVariable(named = "JEORJE_PTY", matches = "1")
class PtySmokeTest {

    @Test
    void scenariosPass() throws Exception {
        var root = findRoot();
        var script = root.resolve("scripts/e2e/run.py");
        var jar = root.resolve("jeorje-cli/target/jeorje.jar");
        assumeTrue(Files.exists(script), "script missing");
        assumeTrue(Files.exists(jar), "jar missing: run mvn package first");
        assumeTrue(pythonAvailable(), "python3 not available");
        var process = new ProcessBuilder("python3", script.toString())
                .redirectErrorStream(true)
                .start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
        assertEquals(0, process.exitValue(), output);
    }

    private static boolean pythonAvailable() {
        try {
            var p = new ProcessBuilder("python3", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Path findRoot() {
        var dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("scripts/e2e/run.py"))) {
            dir = dir.getParent();
        }
        return dir == null ? Path.of("").toAbsolutePath() : dir;
    }
}
