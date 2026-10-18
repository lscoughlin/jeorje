package org.github.lscoughlin.jeorje.tools.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.permissions.ToolPermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.github.lscoughlin.jeorje.tools.registry.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SearchToolsTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    TempWorkspace ws;
    ToolRegistry registry;
    ToolContext context;

    @BeforeEach
    void setUp() {
        ws = new TempWorkspace();
        PermissionPolicy policy = ToolPermissionPolicy.builder(ws.root()).build();
        registry = ToolRegistry.builder()
                .register(new LsTool(policy)).register(new GlobTool(policy)).register(new GrepTool(policy)).build();
        context = new ToolContext(ws.root(), new AgentId("a"), CancellationToken.none(), null, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        ws.close();
    }

    ToolResultData call(String tool, String json) {
        return registry.execute(new ToolUse("id", tool, JSON.readTree(json)), context);
    }

    void touch(String relative, long epochSeconds) throws Exception {
        Files.setLastModifiedTime(ws.resolve(relative), FileTime.fromMillis(epochSeconds * 1000));
    }

    @Test
    void flagsOfSearchTools() {
        for (var name : List.of("ls", "glob", "grep")) {
            assertTrue(registry.find(name).map(Tool::readOnly).orElseThrow(), name);
        }
        var grepSchema = registry.find("grep").orElseThrow().definition().inputSchema();
        assertEquals("content", grepSchema.at("/properties/outputMode/enum/0").asString());
        assertEquals("pattern", grepSchema.at("/required/0").asString());
    }

    // ls

    @Test
    void lsListsDirectoriesFirst() {
        ws.write("b.txt", "12345");
        ws.write("sub/inner.txt", "x");
        ws.write("a.txt", "");
        var result = call("ls", "{}");
        assertFalse(result.error(), result.output());
        assertEquals("sub/\na.txt  0\nb.txt  5\n", result.output());
        assertEquals("inner.txt  1\n", call("ls", "{\"path\":\"sub\"}").output());
    }

    @Test
    void lsPagesAndHidesGit() {
        for (var i = 0; i < 5; i++) ws.write("f" + i, "");
        ws.mkdir(".git");
        var page = call("ls", "{\"limit\":2}").output();
        assertTrue(page.contains("[3 more entries. Use offset 2 to continue.]"), page);
        assertTrue(page.contains("hidden by the permission policy"), page);
        assertFalse(page.contains(".git"));
        var next = call("ls", "{\"offset\":2,\"limit\":2}").output();
        assertTrue(next.startsWith("f2  0\nf3  0\n"), next);
    }

    @Test
    void lsErrors() {
        ws.write("file.txt", "x");
        assertTrue(call("ls", "{\"path\":\"missing\"}").error());
        var notDir = call("ls", "{\"path\":\"file.txt\"}");
        assertTrue(notDir.error());
        assertTrue(call("ls", "{\"path\":\"sub\"}").error());
        assertEquals("(empty)\n", callEmpty());
    }

    String callEmpty() {
        ws.mkdir("empty");
        return call("ls", "{\"path\":\"empty\"}").output();
    }

    // sandbox

    @Test
    void sandboxRefusesPathsOutsideWorkspace() throws Exception {
        try (var other = new TempWorkspace()) {
            other.write("secret.txt", "needle");
            for (var tool : List.of("ls", "glob", "grep")) {
                var input = tool.equals("ls") ? "{\"path\":\"" + other.root() + "\"}"
                        : tool.equals("glob") ? "{\"pattern\":\"**\",\"path\":\"" + other.root() + "\"}"
                        : "{\"pattern\":\"needle\",\"path\":\"" + other.root() + "\"}";
                var result = call(tool, input);
                assertTrue(result.error(), tool);
                assertTrue(result.output().contains("Refused"), result.output());
                assertFalse(result.output().contains("needle"));
            }
            var dotdot = call("grep", "{\"pattern\":\"needle\",\"path\":\"../\"}");
            assertTrue(dotdot.error());
        }
    }

    @Test
    void sandboxRefusesSymlinkToOutside() throws Exception {
        try (var other = new TempWorkspace()) {
            other.write("secret.txt", "needle");
            Files.createSymbolicLink(ws.resolve("link"), other.root());
            ws.write("ok.txt", "needle");
            var result = call("grep", "{\"pattern\":\"needle\"}");
            assertFalse(result.output().contains("secret.txt"), result.output());
            assertTrue(result.output().contains("ok.txt:1:needle"));
            assertTrue(call("grep", "{\"pattern\":\"needle\",\"path\":\"link\"}").error());
        }
    }

    @Test
    void sensitiveFilesAreSkipped() {
        ws.write(".git/config", "needle");
        ws.write(".jeorje/config.yaml", "needle");
        ws.write("src/a.txt", "needle");
        var grep = call("grep", "{\"pattern\":\"needle\"}").output();
        assertEquals("src/a.txt:1:needle\n", grep);
        var glob = call("glob", "{\"pattern\":\"**\"}").output();
        assertEquals("src/a.txt\n", glob);
        assertTrue(call("grep", "{\"pattern\":\"needle\",\"path\":\".git\"}").error());
    }

    @Test
    void denyPolicyRefusesEverything() {
        var denying = new org.github.lscoughlin.jeorje.testkit.FixedPolicy(PermissionPolicy.Decision.DENY);
        var tool = new GrepTool(denying);
        ws.write("a.txt", "needle");
        var reg = ToolRegistry.builder().register(tool).build();
        var result = reg.execute(new ToolUse("id", "grep", JSON.readTree("{\"pattern\":\"needle\"}")), context);
        assertTrue(result.error());
    }

    // glob

    @Test
    void globMatchesAntPatterns() {
        ws.write("src/main/A.java", "");
        ws.write("src/main/deep/B.java", "");
        ws.write("src/test/C.java", "");
        ws.write("src/main/readme.md", "");
        ws.write("Top.java", "");
        assertEquals(List.of("Top.java"), lines(call("glob", "{\"pattern\":\"*.java\"}")));
        assertEquals(List.of("Top.java", "src/main/A.java", "src/main/deep/B.java", "src/test/C.java"),
                sorted(call("glob", "{\"pattern\":\"**/*.java\"}")));
        assertEquals(List.of("src/main/A.java", "src/main/deep/B.java"),
                sorted(call("glob", "{\"pattern\":\"src/main/**/*.java\"}")));
        assertEquals(List.of("src/main/A.java"), sorted(call("glob", "{\"pattern\":\"src/*/A.java\"}")));
        assertEquals(List.of("src/main/A.java"), sorted(call("glob", "{\"pattern\":\"src/main/?.java\"}")));
        assertEquals(List.of("src/test/C.java"), sorted(call("glob", "{\"pattern\":\"src/test/\"}")));
        assertEquals(List.of("src/main/A.java", "src/main/deep/B.java", "src/main/readme.md"),
                sorted(call("glob", "{\"pattern\":\"**\",\"path\":\"src/main\"}")));
        assertEquals("No files match.\n", call("glob", "{\"pattern\":\"*.rs\"}").output());
    }

    @Test
    void globSortsByModificationTimeNewestFirst() throws Exception {
        ws.write("old.txt", "");
        ws.write("mid.txt", "");
        ws.write("new.txt", "");
        touch("old.txt", 1_000);
        touch("mid.txt", 2_000);
        touch("new.txt", 3_000);
        assertEquals(List.of("new.txt", "mid.txt", "old.txt"), lines(call("glob", "{\"pattern\":\"*.txt\"}")));
    }

    @Test
    void globLimitsAndPages() throws Exception {
        for (var i = 0; i < 4; i++) {
            ws.write("f" + i + ".txt", "");
            touch("f" + i + ".txt", 1_000 + i);
        }
        var page = call("glob", "{\"pattern\":\"*.txt\",\"limit\":3}").output();
        assertEquals("f3.txt\nf2.txt\nf1.txt\n[1 more files match. Use offset 3 to continue.]\n", page);
        assertEquals("f0.txt\n", call("glob", "{\"pattern\":\"*.txt\",\"offset\":3}").output());
    }

    @Test
    void globValidatesInput() {
        assertTrue(call("glob", "{}").error());
        assertTrue(call("glob", "{\"pattern\":\"*\",\"limit\":\"ten\"}").error());
    }

    // grep

    @Test
    void grepFindsMatchesWithLineNumbers() {
        ws.write("a.txt", "one\ntwo needle\nthree\n");
        ws.write("sub/b.txt", "needle first\nnothing\n");
        var result = call("grep", "{\"pattern\":\"needle\"}");
        assertEquals("a.txt:2:two needle\nsub/b.txt:1:needle first\n", result.output());
        assertEquals("sub/b.txt:1:needle first\n",
                call("grep", "{\"pattern\":\"needle\",\"path\":\"sub\"}").output());
        assertEquals("a.txt:2:two needle\n", call("grep", "{\"pattern\":\"needle\",\"path\":\"a.txt\"}").output());
        assertTrue(call("grep", "{\"pattern\":\"absent\"}").output().startsWith("No matches in 2 files."));
    }

    @Test
    void grepRegexAndIgnoreCase() {
        ws.write("a.txt", "Alpha 123\nbeta\n");
        assertEquals("a.txt:1:Alpha 123\n", call("grep", "{\"pattern\":\"Alpha \\\\d+\"}").output());
        assertTrue(call("grep", "{\"pattern\":\"ALPHA\"}").output().startsWith("No matches"));
        assertEquals("a.txt:1:Alpha 123\n", call("grep", "{\"pattern\":\"ALPHA\",\"ignoreCase\":true}").output());
        var bad = call("grep", "{\"pattern\":\"(unclosed\"}");
        assertTrue(bad.error());
        assertTrue(bad.output().contains("Invalid regular expression"));
    }

    @Test
    void grepContextLines() {
        ws.write("a.txt", "l1\nl2\nHIT\nl4\nl5\nl6\nl7\nl8\nHIT\nl10\n");
        var result = call("grep", "{\"pattern\":\"HIT\",\"context\":1}").output();
        assertEquals("a.txt-2-l2\na.txt:3:HIT\na.txt-4-l4\n--\na.txt-8-l8\na.txt:9:HIT\na.txt-10-l10\n", result);
        var after = call("grep", "{\"pattern\":\"HIT\",\"after\":1,\"before\":0}").output();
        assertEquals("a.txt:3:HIT\na.txt-4-l4\n--\na.txt:9:HIT\na.txt-10-l10\n", after);
        var merged = call("grep", "{\"pattern\":\"HIT\",\"context\":10}").output();
        assertFalse(merged.contains("--"));
        assertEquals(10, merged.lines().count());
    }

    @Test
    void grepFileFilters() {
        ws.write("a.java", "needle");
        ws.write("src/b.java", "needle");
        ws.write("src/c.txt", "needle");
        assertEquals("a.java:1:needle\nsrc/b.java:1:needle\n",
                call("grep", "{\"pattern\":\"needle\",\"glob\":\"*.java\"}").output());
        assertEquals("src/b.java:1:needle\n",
                call("grep", "{\"pattern\":\"needle\",\"glob\":\"src/*.java\"}").output());
    }

    @Test
    void grepOutputModes() {
        ws.write("a.txt", "x\nx\nx\n");
        ws.write("b.txt", "x\n");
        ws.write("c.txt", "y\n");
        assertEquals("a.txt\nb.txt\n", call("grep", "{\"pattern\":\"x\",\"outputMode\":\"files\"}").output());
        assertEquals("a.txt:3\nb.txt:1\n", call("grep", "{\"pattern\":\"x\",\"outputMode\":\"count\"}").output());
        var invalid = call("grep", "{\"pattern\":\"x\",\"outputMode\":\"json\"}");
        assertTrue(invalid.error());
        assertTrue(invalid.output().contains("must be one of"));
    }

    @Test
    void grepCapsMatchesWithOffsetHint() {
        ws.write("a.txt", "m\n".repeat(10));
        var first = call("grep", "{\"pattern\":\"m\",\"limit\":4}").output();
        assertEquals(4, first.lines().filter(l -> l.startsWith("a.txt:")).count());
        assertTrue(first.contains("[More results exist. Use offset 4 to continue.]"), first);
        var second = call("grep", "{\"pattern\":\"m\",\"limit\":4,\"offset\":8}").output();
        assertEquals("a.txt:9:m\na.txt:10:m\n", second);
    }

    @Test
    void grepCutsLongLines() {
        ws.write("a.txt", "needle" + "z".repeat(2000) + "\n");
        var out = call("grep", "{\"pattern\":\"needle\"}").output();
        assertTrue(out.contains("[line cut]"));
        assertTrue(out.length() < 700);
    }

    @Test
    void registryCapsHugeGrepOutput() {
        var big = ("x".repeat(100) + "\n").repeat(400);
        for (var i = 0; i < 3; i++) ws.write("f" + i + ".txt", big);
        var out = call("grep", "{\"pattern\":\"x\",\"limit\":5000}").output();
        assertTrue(out.contains("[Output cut: showing 30000 of"), out.substring(Math.max(0, out.length() - 300)));
    }

    @Test
    void grepSkipsBinaryAndLargeFiles() throws Exception {
        ws.write("text.txt", "needle");
        Files.write(ws.resolve("data.bin"), new byte[] {'n', 'e', 'e', 'd', 'l', 'e', 0, 1, 2});
        Files.write(ws.resolve("big.txt"), ("needle\n".repeat(1_000_000)).getBytes());
        var out = call("grep", "{\"pattern\":\"needle\"}").output();
        assertTrue(out.contains("text.txt:1:needle"));
        assertFalse(out.contains("data.bin"));
        assertFalse(out.contains("big.txt"));
        assertTrue(out.contains("[2 binary or large files skipped.]"), out);
    }

    @Test
    void grepStopsRunawayRegexOnTimeout() {
        ws.write("a.txt", "x".repeat(40) + "\n");
        var slowRegistry = ToolRegistry.builder()
                .register(new GrepTool(ToolPermissionPolicy.builder(ws.root()).build()))
                .timeout("grep", java.time.Duration.ofMillis(300)).build();
        var start = System.nanoTime();
        var result = slowRegistry.execute(new ToolUse("id", "grep",
                JSON.readTree("{\"pattern\":\"(x+)+\\\\1y\"}")), context);
        assertTrue(result.error(), result.output());
        assertTrue(result.output().contains("timed out"), result.output());
        assertTrue((System.nanoTime() - start) / 1_000_000_000L < 5);
    }

    @Test
    void concurrentSearchCallsWork() {
        ws.write("a.txt", "needle");
        var uses = List.of(
                new ToolUse("1", "ls", JSON.readTree("{}")),
                new ToolUse("2", "glob", JSON.readTree("{\"pattern\":\"*\"}")),
                new ToolUse("3", "grep", JSON.readTree("{\"pattern\":\"needle\"}")));
        assertTrue(registry.canRunTogether(uses));
        var results = registry.executeAll(uses, context);
        assertEquals(3, results.size());
        assertTrue(results.stream().noneMatch(ToolResultData::error));
    }

    private static List<String> lines(ToolResultData result) {
        assertFalse(result.error(), result.output());
        return result.output().lines().toList();
    }

    private static List<String> sorted(ToolResultData result) {
        return lines(result).stream().sorted().toList();
    }
}
