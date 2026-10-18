package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.port.Filters;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;
import org.github.lscoughlin.jeorje.core.port.Syntax;
import org.github.lscoughlin.jeorje.core.session.SessionStore;
import org.github.lscoughlin.jeorje.core.session.TranscriptReader;
import org.github.lscoughlin.jeorje.index.store.LuceneContextStore;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.github.lscoughlin.jeorje.testkit.StubEmbedder;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.junit.jupiter.api.Test;

class RunCommandTest {

    @Test
    void runsATaskWithTheScriptedClient() throws Exception {
        var llm = new ScriptedLlmClient(
                Response.toolCall("t1", "write", "{\"path\":\"hello.txt\",\"content\":\"zebrafish content\\n\"}"),
                Response.text("I wrote hello.txt."));
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), llm);
            var code = cli.run("run", "--yes", "Create hello.txt");
            assertEquals(0, code, cli.err());

            // The file and the final answer.
            assertEquals("zebrafish content\n", cwd.read("hello.txt"));
            assertEquals("I wrote hello.txt.\n", cli.out());
            assertTrue(cli.err().contains("WARNING: --yes"), cli.err());
            assertEquals(2, llm.callCount());

            // The session on disk.
            var sessions = new SessionStore(cwd.root()).list();
            assertEquals(1, sessions.size());
            var session = sessions.getFirst();
            assertEquals("idle", session.manifest().runState());
            assertEquals(1, session.manifest().lastTurn());
            assertTrue(cwd.exists(".jeorje/.gitignore"));
            assertTrue(Files.exists(session.dir().resolve("audit.jsonl")));

            // The transcript.
            var turns = TranscriptReader.read(session.transcriptPath());
            assertEquals(1, turns.size());
            var messages = turns.getFirst().turn().messages();
            assertEquals(Role.USER, messages.getFirst().role());
            assertTrue(messages.getFirst().content().toString().contains("Create hello.txt"));
            assertTrue(messages.stream().anyMatch(m -> m.content().toString().contains("write")));
            assertTrue(messages.getLast().content().toString().contains("I wrote hello.txt."));

            // The index: reopen it and search.
            try (var store = LuceneContextStore.open(session.indexDir(), new StubEmbedder(),
                    LuceneContextStore.Options.defaults())) {
                var hits = store.search(new HistoryQuery("zebrafish", Syntax.TEXT, Filters.none(), 5));
                assertFalse(hits.isEmpty(), "the index holds the turn");
            }
        }
    }

    @Test
    void withoutYesAWriteNeedsApprovalAndIsNotDone() throws Exception {
        var llm = new ScriptedLlmClient(
                Response.toolCall("t1", "write", "{\"path\":\"x.txt\",\"content\":\"x\"}"),
                Response.text("done"));
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), llm);
            var code = cli.run("run", "--dir", cwd.root().toString(), "Create x.txt");
            // The run mode has no user to ask. A call that needs approval must not run.
            assertFalse(cwd.exists("x.txt"));
            assertTrue(code == 0 || code == 1);
        }
    }

    @Test
    void yoloPrintsItsOwnWarning() {
        var llm = new ScriptedLlmClient(Response.text("ok"));
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), llm);
            assertEquals(0, cli.run("--yolo", "run", "hi"), cli.err());
            assertTrue(cli.err().contains("WARNING: --yolo"));
            assertEquals("ok\n", cli.out());
        }
    }

    @Test
    void missingApiKeyIsAUsageError() {
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), null);
            assertEquals(2, cli.run("run", "hi"));
            assertTrue(cli.err().contains("No API key"), cli.err());
        }
    }

    @Test
    void indexRebuildReadsTheTranscript() {
        var llm = new ScriptedLlmClient(Response.text("the walrus answer"));
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var cli = new CliTestSupport(home.root(), cwd.root(), Map.of(), llm);
            assertEquals(0, cli.run("run", "hello walrus"), cli.err());
            assertEquals(0, cli.run("index", "rebuild"), cli.err());
            assertTrue(cli.out().contains("Rebuilt the index"), cli.out());
            assertEquals(0, cli.run("resume"), cli.err());
            assertTrue(cli.out().contains("needs a terminal"), cli.out());
        }
    }
}
