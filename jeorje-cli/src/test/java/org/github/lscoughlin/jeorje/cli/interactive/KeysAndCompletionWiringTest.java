package org.github.lscoughlin.jeorje.cli.interactive;

import static org.github.lscoughlin.jeorje.cli.interactive.SessionRig.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.cli.CliException;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The key config, the pane keys, and the Tab completion in the real session. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class KeysAndCompletionWiringTest {
    private static final AgentId MAIN = new AgentId("main");

    @TempDir Path tmp;

    private static void ready(SessionRig rig) throws InterruptedException {
        await("screen up", () -> rig.session.screen() != null && rig.session.slash() != null
                && rig.terminalText().contains("ready"));
    }

    @Test
    void aKeyConflictInTheConfigStopsTheStart() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        var config = "ui:\n  keys:\n    agents: \"Ctrl-X f\"\n";
        try (var rig = new SessionRig(tmp, router, config)) {
            rig.initRepo();
            rig.session = new InteractiveSession(rig.env(), rig.options(), rig.terminal,
                    new InteractiveSession.Hooks(tmp.resolve("history"), java.time.Duration.ofMillis(50), null));
            var e = assertThrows(CliException.class, rig.session::run);
            assertTrue(e.getMessage().contains("key config has an error"), e.getMessage());
            assertTrue(e.getMessage().contains("'agents'") && e.getMessage().contains("'files'"), e.getMessage());
            assertTrue(e.getMessage().contains("Ctrl-X f"), e.getMessage());
            assertTrue(rig.session.screen() == null, "the screen did not start");
        }
    }

    @Test
    void reboundKeysOpenThePanesAndTheOldChordsDoNothing() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        var config = "ui:\n  keys:\n    agents: \"Ctrl-X g\"\n    files: F3\n";
        try (var rig = new SessionRig(tmp, router, config)) {
            rig.initRepo();
            rig.start();
            ready(rig);
            rig.type("\u0018a\u0018f");
            Thread.sleep(400);
            assertFalse(rig.session.screen().paneActive(), "the old chords open no pane");
            rig.type("\u0018g");
            await("switcher", () -> rig.session.screen().paneActive());
            rig.type("\u0018g");
            await("switcher closed by the same chord", () -> !rig.session.screen().paneActive());
            rig.type("\u001bOR");
            await("browser", () -> rig.session.screen().paneActive());
            rig.type("\u001bOR");
            await("browser closed by the same key", () -> !rig.session.screen().paneActive());
        }
    }

    @Test
    void theDefaultChordToggleClosesTheSwitcher() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            ready(rig);
            rig.type("\u0018a");
            await("switcher", () -> rig.session.screen().paneActive());
            rig.type("\u0018a");
            await("switcher closed", () -> !rig.session.screen().paneActive());
        }
    }

    @Test
    void tabCompletesACommandThatThenRuns() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            ready(rig);
            rig.type("/he\t\r");
            await("help text", () -> rig.session.screen().chat(MAIN).plainText().contains("/quit"));
            assertTrue(router.mainRequests.isEmpty(), "the completed command is not a prompt");
        }
    }

    @Test
    void tabListsSeveralCandidates() throws Exception {
        var router = new SessionRig.Router(new ScriptedLlmClient());
        try (var rig = new SessionRig(tmp, router, null)) {
            rig.initRepo();
            rig.start();
            ready(rig);
            rig.type("/co\t");
            await("candidate list", () -> {
                var text = rig.session.screen().chat(MAIN).plainText();
                return text.contains("matches:") && text.contains("/compact") && text.contains("/commands");
            });
        }
    }
}
