package org.github.lscoughlin.jeorje.cli.interactive;

import static org.github.lscoughlin.jeorje.cli.interactive.SessionRig.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The file browser and {@code /files list} show the paths that {@code .gitignore} lists. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class FilesListIgnoredTest {
    @TempDir Path tmp;

    @Test
    void theListShowsIgnoredPathsAndHidesTheDataOfJeorje() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            Files.writeString(rig.repo.resolve(".gitignore"), ".jeorje/\ntarget/\nbuild/\n*.log\n");
            Files.createDirectories(rig.repo.resolve("build"));
            Files.writeString(rig.repo.resolve("build/out.class"), "x");
            Files.writeString(rig.repo.resolve("debug.log"), "x");
            rig.start();
            await("screen up", () -> rig.session.screen() != null && rig.session.slash() != null
                    && rig.terminalText().contains("ready"));
            var output = rig.session.slash().execute("/files list").output();
            assertTrue(output.contains("build/"), output);
            assertTrue(output.contains("debug.log"), output);
            assertTrue(output.contains("README.md"), output);
            assertTrue(output.contains(".jeorje/"), output);
            assertFalse(output.contains("sessions"), output);
            assertFalse(output.contains(".git/"), output);
            var filtered = rig.session.slash().execute("/files list out").output();
            assertTrue(filtered.contains("out.class"), filtered);
        }
    }
}
