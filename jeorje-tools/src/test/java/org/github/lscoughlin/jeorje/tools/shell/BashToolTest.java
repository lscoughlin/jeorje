package org.github.lscoughlin.jeorje.tools.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BashToolTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    TempWorkspace ws;
    BashTool tool;
    CancellationToken cancel;
    ToolContext context;
    final List<String> streamed = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        ws = new TempWorkspace();
        var env = Map.of("PATH", System.getenv("PATH"), "HOME", "/tmp", "OPENROUTER_API_KEY", "sk-secret",
                "MY_TOKEN", "t0k", "RANDOM_THING", "x");
        var settings = ShellSettings.defaults().withSourceEnvironment(env).withLimits(100, 100)
                .withListener((id, text) -> streamed.add(text));
        tool = new BashTool(null, settings);
        newContext(CancellationToken.none());
    }

    void newContext(CancellationToken token) {
        cancel = token;
        context = new ToolContext(ws.root(), new AgentId("a"), token, null, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        tool.close();
        ws.close();
    }

    ToolResultData call(String json) throws Exception {
        return tool.execute(JSON.readTree(json), context);
    }

    static String quote(String s) {
        return JSON.writeValueAsString(s);
    }

    @Test
    void exitCodesAndStderr() throws Exception {
        var ok = call("{\"command\":\"echo hello\"}");
        assertFalse(ok.error());
        assertTrue(ok.output().contains("hello"));
        assertTrue(ok.output().contains("[exit code 0]"));
        var bad = call("{\"command\":\"echo oops >&2; exit 3\"}");
        assertTrue(bad.error());
        assertTrue(bad.output().contains("oops"));
        assertTrue(bad.output().contains("[exit code 3]"));
    }

    @Test
    void runsInWorkingDirectory() throws Exception {
        Files.writeString(ws.root().resolve("marker.txt"), "x");
        assertTrue(call("{\"command\":\"ls\"}").output().contains("marker.txt"));
    }

    @Test
    void environmentIsFiltered() throws Exception {
        var out = call("{\"command\":\"env\"}").output();
        assertTrue(out.contains("PATH="));
        assertFalse(out.contains("sk-secret"));
        assertFalse(out.contains("OPENROUTER_API_KEY"));
        assertFalse(out.contains("MY_TOKEN"));
        assertFalse(out.contains("RANDOM_THING"));
    }

    @Test
    void longOutputKeepsStartAndEnd() throws Exception {
        var out = call("{\"command\":\"echo START; seq 1 5000; echo FINISH\"}").output();
        assertTrue(out.startsWith("START"));
        assertTrue(out.contains("FINISH"));
        assertTrue(out.contains("characters of output omitted"));
        assertTrue(out.length() < 600);
    }

    @Test
    void outputStreamsToListener() throws Exception {
        call("{\"command\":\"echo one; echo two\"}");
        assertTrue(String.join("", streamed).contains("one"));
        assertTrue(String.join("", streamed).contains("two"));
    }

    @Test
    void timeoutKillsCommand() throws Exception {
        var start = System.nanoTime();
        var result = call("{\"command\":\"echo before; sleep 30\",\"timeout\":1}");
        assertTrue(result.error());
        assertTrue(result.output().contains("timed out"));
        assertTrue(result.output().contains("before"));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 15_000);
    }

    @Test
    void timeoutKillsChildProcesses() throws Exception {
        var pidFile = ws.root().resolve("child.pid");
        var command = "sleep 60 & echo $! > child.pid; wait";
        var result = call("{\"command\":" + quote(command) + ",\"timeout\":1}");
        assertTrue(result.error());
        var pid = Long.parseLong(Files.readString(pidFile).trim());
        assertGone(pid);
    }

    void assertGone(long pid) throws Exception {
        for (var i = 0; i < 50; i++) {
            if (ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true)) return;
            Thread.sleep(100);
        }
        throw new AssertionError("process " + pid + " is still alive");
    }

    @Test
    void cancellationKillsTree() throws Exception {
        var token = CancellationToken.none();
        newContext(token);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(700);
            } catch (InterruptedException ignored) {
            }
            token.cancel();
        });
        var result = call("{\"command\":\"sleep 60 & echo $! > child.pid; wait\"}");
        assertTrue(result.error());
        assertTrue(result.output().contains("cancelled"));
        assertGone(Long.parseLong(Files.readString(ws.root().resolve("child.pid")).trim()));
    }

    @Test
    void backgroundPollAndKill() throws Exception {
        var started = call("{\"command\":\"echo first; sleep 60\",\"background\":true}").output();
        var id = started.replaceAll("(?s).*process (bg-\\d+)\\..*", "$1");
        assertTrue(id.startsWith("bg-"), started);
        String polled = "";
        for (var i = 0; i < 50 && !polled.contains("first"); i++) {
            Thread.sleep(100);
            polled = call("{\"operation\":\"poll\",\"id\":\"" + id + "\"}").output();
        }
        assertTrue(polled.contains("first"));
        assertTrue(polled.contains("is running"));
        // a second poll shows no repeated output
        assertFalse(call("{\"operation\":\"poll\",\"id\":\"" + id + "\"}").output().contains("first"));
        var killed = call("{\"operation\":\"kill\",\"id\":\"" + id + "\"}");
        assertTrue(killed.output().contains("was killed"));
        assertTrue(call("{\"operation\":\"poll\",\"id\":\"" + id + "\"}").error());
    }

    @Test
    void backgroundPollReportsExit() throws Exception {
        var started = call("{\"command\":\"echo done; exit 4\",\"background\":true}").output();
        var id = started.replaceAll("(?s).*process (bg-\\d+)\\..*", "$1");
        String polled = "";
        for (var i = 0; i < 50 && !polled.contains("exited"); i++) {
            Thread.sleep(100);
            polled = call("{\"operation\":\"poll\",\"id\":\"" + id + "\"}").output();
        }
        assertTrue(polled.contains("done"));
        assertTrue(polled.contains("exited with code 4"));
    }

    @Test
    void backgroundKillRemovesChildren() throws Exception {
        var started = call("{\"command\":\"sleep 60 & echo $! > child.pid; wait\",\"background\":true}").output();
        var id = started.replaceAll("(?s).*process (bg-\\d+)\\..*", "$1");
        for (var i = 0; i < 50 && !Files.exists(ws.root().resolve("child.pid")); i++) Thread.sleep(100);
        Thread.sleep(200);
        call("{\"operation\":\"kill\",\"id\":\"" + id + "\"}");
        assertGone(Long.parseLong(Files.readString(ws.root().resolve("child.pid")).trim()));
    }

    @Test
    void refusesWhenPolicyDenies() throws Exception {
        PermissionPolicy deny = action -> PermissionPolicy.Decision.DENY;
        var denied = new BashTool(deny, ShellSettings.defaults());
        var result = denied.execute(JSON.readTree("{\"command\":\"touch x.txt\"}"), context);
        assertTrue(result.error());
        assertFalse(Files.exists(ws.root().resolve("x.txt")));
    }

    @Test
    void emptyCommandAndUnknownId() throws Exception {
        assertTrue(call("{\"command\":\" \"}").error());
        assertTrue(call("{\"operation\":\"kill\",\"id\":\"nope\"}").error());
    }

    @Test
    void outputBufferKeepsHeadAndTail() {
        var buffer = new OutputBuffer(3, 3);
        buffer.append("abcdefghij");
        var text = buffer.render();
        assertTrue(text.startsWith("abc"));
        assertTrue(text.endsWith("hij"));
        assertEquals(10, buffer.total());
    }

    @Test
    void stopProcessesInKillsBackgroundProcessesOfTheDirectory() throws Exception {
        var result = call("{\"command\":\"sleep 30\",\"background\":true}");
        assertFalse(result.error());
        var id = result.output().replaceAll("(?s).*process (bg-\\d+)\\..*", "$1");
        assertEquals(1, BashTool.stopProcessesIn(ws.root()));
        assertEquals(0, BashTool.stopProcessesIn(ws.root()), "the registry has no process left");
        var poll = call("{\"operation\":\"poll\",\"id\":" + quote(id) + "}");
        assertTrue(poll.output().contains("exited"), poll.output());
    }

    @Test
    void stopProcessesInIgnoresOtherDirectories() throws Exception {
        call("{\"command\":\"sleep 30\",\"background\":true}");
        assertEquals(0, BashTool.stopProcessesIn(ws.root().resolveSibling("elsewhere")));
        assertEquals(1, BashTool.stopProcessesIn(ws.root()));
    }

    @Test
    void configuredDefaultTimeoutApplies() throws Exception {
        var custom = new BashTool(null, ShellSettings.defaults(), 1);
        try {
            var start = System.nanoTime();
            var result = custom.execute(JSON.readTree("{\"command\":\"sleep 30\"}"), context);
            assertTrue(result.error());
            assertTrue(result.output().contains("timed out"), result.output());
            assertTrue((System.nanoTime() - start) / 1_000_000 < 15_000);
            // A call can still ask for another timeout. The configured value is only the default.
            var quick = custom.execute(JSON.readTree("{\"command\":\"echo ok\",\"timeout\":5}"), context);
            assertFalse(quick.error(), quick.output());
        } finally {
            custom.close();
        }
    }

    @Test
    void configuredTimeoutAboveTheOldMaximumRaisesTheMaximum() {
        assertEquals(java.time.Duration.ofSeconds(900 + 30L), new BashTool(null, ShellSettings.defaults(), 900).timeout());
        assertEquals(java.time.Duration.ofSeconds(BashTool.MAX_TIMEOUT_SECONDS + 30L),
                new BashTool(null, ShellSettings.defaults(), 60).timeout());
    }
}
