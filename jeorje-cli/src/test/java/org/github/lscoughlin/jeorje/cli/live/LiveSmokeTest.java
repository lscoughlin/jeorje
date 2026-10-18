package org.github.lscoughlin.jeorje.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.github.lscoughlin.jeorje.cli.CliEnvironment;
import org.github.lscoughlin.jeorje.cli.JeorjeMain;
import org.github.lscoughlin.jeorje.core.port.Filters;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;
import org.github.lscoughlin.jeorje.core.port.Syntax;
import org.github.lscoughlin.jeorje.core.session.SessionStore;
import org.github.lscoughlin.jeorje.core.session.TranscriptReader;
import org.github.lscoughlin.jeorje.index.store.LuceneContextStore;
import org.github.lscoughlin.jeorje.testkit.StubEmbedder;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs the full agent against OpenRouter with the default model. It runs only when JEORJE_LIVE=1 and
 * needs OPENROUTER_API_KEY. It never prints the key.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "JEORJE_LIVE", matches = "1")
class LiveSmokeTest {

    @Test
    void createsAFileAndNeverLeaksTheKey() throws Exception {
        var key = System.getenv("OPENROUTER_API_KEY");
        assertTrue(key != null && !key.isBlank(), "OPENROUTER_API_KEY is not set");

        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        try (var home = new TempWorkspace(); var cwd = new TempWorkspace()) {
            var env = new CliEnvironment(home.root(), cwd.root(), Map.of("OPENROUTER_API_KEY", key),
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8), null,
                    config -> Optional.of(new StubEmbedder()), false);
            var code = JeorjeMain.execute(env, new String[] {"run", "--yes",
                    "create a file hello.txt with the text hello"});
            var errText = err.toString(StandardCharsets.UTF_8);
            assertEquals(0, code, scrub(errText, key));

            // The file exists and holds the text.
            assertTrue(cwd.exists("hello.txt"), "hello.txt exists");
            assertTrue(cwd.read("hello.txt").toLowerCase().contains("hello"));

            // The transcript has a tool call and a final answer.
            var sessions = new SessionStore(cwd.root()).list();
            assertEquals(1, sessions.size());
            var session = sessions.getFirst();
            var turns = TranscriptReader.read(session.transcriptPath());
            assertEquals(1, turns.size());
            var messages = turns.getFirst().turn().messages();
            assertTrue(messages.stream().anyMatch(m -> m.content().toString().contains("ToolUse")
                    || m.content().toString().contains("write")), "the transcript has a tool call");

            // The index has the turn.
            try (var store = LuceneContextStore.open(session.indexDir(), new StubEmbedder(),
                    LuceneContextStore.Options.defaults())) {
                var hits = store.search(new HistoryQuery("hello", Syntax.TEXT, Filters.none(), 5));
                assertFalse(hits.isEmpty(), "the index holds the turn");
            }

            // The key value is in no output, log, transcript, or index file.
            assertFalse(out.toString(StandardCharsets.UTF_8).contains(key), "key in standard output");
            assertFalse(errText.contains(key), "key in standard error");
            assertKeyAbsent(home.root(), key);
            assertKeyAbsent(cwd.root(), key);
        }
    }

    private static String scrub(String text, String key) {
        return text.replace(key, "***");
    }

    /** Fails when any file below the root holds the key. The failure message never holds the key. */
    private static void assertKeyAbsent(Path root, String key) throws IOException {
        var needle = key.getBytes(StandardCharsets.UTF_8);
        try (var files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                assertFalse(contains(Files.readAllBytes(file), needle), "key found in " + root.relativize(file));
            }
        }
    }

    private static boolean contains(byte[] data, byte[] needle) {
        outer:
        for (var i = 0; i <= data.length - needle.length; i++) {
            for (var j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
