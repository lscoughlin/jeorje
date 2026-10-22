package org.github.lscoughlin.jeorje.tools.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShellRunnerTest {
    private static final Path DIR = Path.of(".").toAbsolutePath();

    @Test
    void runsACommand() {
        var result = ShellRunner.run(DIR, "echo hello", ShellSettings.defaults(), Duration.ofSeconds(20));
        assertTrue(result.output().contains("hello"));
        assertTrue(result.ok());
        assertFalse(result.timedOut());
    }

    @Test
    void reportsTheExitCode() {
        var result = ShellRunner.run(DIR, "exit 3", ShellSettings.defaults(), Duration.ofSeconds(20));
        assertEquals(3, result.exitCode());
        assertFalse(result.ok());
    }

    @Test
    void shortensLongOutput() {
        var command = "i=0; while [ $i -lt 4000 ]; do printf 0123456789; i=$((i+1)); done; echo END";
        var result = ShellRunner.run(DIR, command, ShellSettings.defaults().withLimits(100, 100),
                Duration.ofSeconds(20));
        assertTrue(result.output().length() < 400, "length " + result.output().length());
        assertTrue(result.output().startsWith("0123456789"));
        assertTrue(result.output().contains("END"));
    }

    @Test
    void killsACommandThatRunsTooLong() {
        var start = System.nanoTime();
        var result = ShellRunner.run(DIR, "sleep 5", ShellSettings.defaults(), Duration.ofSeconds(1));
        var millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertTrue(result.timedOut());
        assertFalse(result.ok());
        assertTrue(millis < 3500, "took " + millis + " ms");
    }

    @Test
    void doesNotThrowWhenTheShellIsMissing() {
        var settings = ShellSettings.defaults().withShell(List.of("/no/such/shell"));
        var result = ShellRunner.run(DIR, "echo hi", settings, Duration.ofSeconds(5));
        assertEquals(-1, result.exitCode());
        assertTrue(result.output().startsWith("[cannot start the command"));
    }
}
