package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.github.lscoughlin.jeorje.core.session.SessionStore;
import org.github.lscoughlin.jeorje.core.session.TranscriptReader;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.junit.jupiter.api.Test;

class ResumeTest {

    @Test
    void aResumedSessionBuildsTheSameRequestAndTheModelContinuesTheTask() throws Exception {
        var first = new ScriptedLlmClient(Response.text("one done"), Response.text("two done"),
                Response.text("three done"), Response.text("four done"));
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), first);
            var loaded = Wiring.loadConfig(new GlobalOptions(), cli.env);
            String sessionId;
            java.nio.file.Path transcriptFile;
            byte[] savedTranscript;
            try (var runtime = Wiring.open(loaded, new GlobalOptions(), cli.env)) {
                sessionId = runtime.session().id();
                runtime.agent().runTurn("first task about walruses");
                runtime.agent().pin("Always answer in short sentences.");
                runtime.agent().runTurn("second task");
                runtime.agent().runTurn("third task");
                runtime.saveManifest("idle");
                transcriptFile = runtime.session().transcriptPath();
                savedTranscript = java.nio.file.Files.readAllBytes(transcriptFile);
                // The request that the first run builds for the next turn.
                runtime.agent().runTurn("fourth task");
            }
            // Put the session back to the state after three turns, as if the process had ended there.
            java.nio.file.Files.write(transcriptFile, savedTranscript);
            var expected = first.requests().get(3);

            // A new process resumes the session and continues with a scripted model.
            var second = new ScriptedLlmClient(Response.text("resumed answer"));
            var cli2 = new CliTestSupport(home.root(), cwd.root(), Map.of(), second);
            var code = cli2.run("resume", sessionId, "--task", "fourth task");
            assertEquals(0, code, cli2.err());
            assertEquals("resumed answer\n", cli2.out().substring(cli2.out().indexOf("resumed answer")));
            assertTrue(cli2.out().contains("Session " + sessionId), cli2.out());

            var actual = second.requests().getFirst();
            assertTrue(expected.equals(actual), "the working context equals the context of the first run");
            assertTrue(actual.toString().contains("Always answer in short sentences."));
            assertTrue(actual.toString().contains("first task about walruses"));

            // The resumed run continued the same session: four turns in the transcript, the manifest is current.
            var session = new SessionStore(cwd.root()).resume(sessionId).orElseThrow();
            assertEquals(4, session.manifest().lastTurn());
            assertEquals(1, session.manifest().pinned().size());
            assertEquals(4, TranscriptReader.read(session.transcriptPath()).size());
            assertEquals(1, new SessionStore(cwd.root()).list().size(), "no new session was made");
        }
    }

    @Test
    void resumeOfAnUnknownSessionIsAnError() {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), new ScriptedLlmClient());
            assertEquals(2, cli.run("resume", "does-not-exist"));
            assertTrue(cli.err().contains("Session not found"), cli.err());
            assertEquals(2, cli.run("resume"));
            assertTrue(cli.err().contains("No session found"), cli.err());
        }
    }

    @Test
    void resumeWithoutATaskOpensTheInterfaceAndNeedsATerminal() {
        var llm = new ScriptedLlmClient(Response.text("ok"));
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), llm);
            assertEquals(0, cli.run("run", "hello"), cli.err());
            assertEquals(0, cli.run("resume"), cli.err());
            assertTrue(cli.out().contains("needs a terminal"), cli.out());
            // The flag of the root command goes the same way.
            assertEquals(0, cli.run("--resume"), cli.err());
        }
    }
}
