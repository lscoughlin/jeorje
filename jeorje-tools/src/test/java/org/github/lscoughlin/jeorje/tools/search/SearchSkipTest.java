package org.github.lscoughlin.jeorje.tools.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.github.lscoughlin.jeorje.core.config.SecretScrubber;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.permissions.AuditLog;
import org.github.lscoughlin.jeorje.core.permissions.ToolPermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.github.lscoughlin.jeorje.tools.registry.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The search tools skip build and editor paths, and the audit log gets one entry for each call. */
class SearchSkipTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    TempWorkspace ws;
    ToolRegistry registry;
    ToolContext context;
    AuditLog audit;

    @BeforeEach
    void setUp() {
        ws = new TempWorkspace();
        audit = new AuditLog(ws.root().resolve("audit.jsonl"), new SecretScrubber(), Clock.systemUTC());
        var policy = ToolPermissionPolicy.builder(ws.root()).audit(audit).build();
        registry = ToolRegistry.builder().register(new LsTool(policy)).register(new GlobTool(policy))
                .register(new GrepTool(policy)).build();
        context = new ToolContext(ws.root(), new AgentId("a"), CancellationToken.none(), null, Clock.systemUTC());
        ws.write("src/A.java", "class A { String zebra; }");
        ws.write("target/classes/A.class", "zebra");
        ws.write("target/x.txt", "zebra");
        ws.write(".idea/a.xml", "zebra");
        ws.write("node_modules/m/index.js", "zebra");
        ws.write(".DS_Store", "zebra");
        ws.write("lib/x.jar", "zebra");
    }

    @AfterEach
    void tearDown() {
        ws.close();
    }

    ToolResultData call(String tool, String json) {
        return registry.execute(new ToolUse("id", tool, JSON.readTree(json)), context);
    }

    @Test
    void grepAndGlobSkipBuildOutputByDefaultAndSayHowMany() {
        var grep = call("grep", "{\"pattern\":\"zebra\",\"outputMode\":\"files\"}");
        assertTrue(grep.output().startsWith("src/A.java\n"), grep.output());
        assertFalse(grep.output().contains("target") || grep.output().contains(".idea") || grep.output().contains("DS_Store"),
                grep.output());
        assertTrue(grep.output().contains("build or editor paths were skipped. Use includeIgnored to search them."),
                grep.output());
        var glob = call("glob", "{\"pattern\":\"**/*\"}");
        assertTrue(glob.output().contains("src/A.java") && !glob.output().contains("target/"), glob.output());
        assertTrue(glob.output().contains("were skipped"), glob.output());
    }

    @Test
    void includeIgnoredSearchesEverything() {
        var grep = call("grep", "{\"pattern\":\"zebra\",\"outputMode\":\"files\",\"includeIgnored\":true}");
        for (var name : List.of("src/A.java", "target/classes/A.class", "target/x.txt", ".idea/a.xml",
                "node_modules/m/index.js", ".DS_Store", "lib/x.jar")) {
            assertTrue(grep.output().contains(name) || name.endsWith(".class") || name.endsWith(".jar"), name + ": " + grep.output());
        }
        assertTrue(grep.output().contains("target/x.txt") && grep.output().contains(".idea/a.xml"), grep.output());
        assertFalse(grep.output().contains("were skipped"), grep.output());
        var glob = call("glob", "{\"pattern\":\"**/*\",\"includeIgnored\":true}");
        assertTrue(glob.output().contains("lib/x.jar") && glob.output().contains("target/classes/A.class"), glob.output());
    }

    @Test
    void aSearchThatStartsInsideASkippedDirectorySearchesIt() {
        var grep = call("grep", "{\"pattern\":\"zebra\",\"path\":\"target\",\"outputMode\":\"files\"}");
        assertTrue(grep.output().contains("target/x.txt"), grep.output());
    }

    @Test
    void lsHidesSkippedNamesAndSaysSo() {
        var ls = call("ls", "{}");
        assertTrue(ls.output().contains("src/") && ls.output().contains("lib/"), ls.output());
        assertFalse(ls.output().contains("target/") || ls.output().contains(".DS_Store"), ls.output());
        assertTrue(ls.output().contains("were skipped"), ls.output());
        var all = call("ls", "{\"includeIgnored\":true}");
        assertTrue(all.output().contains("target/") && all.output().contains(".DS_Store"), all.output());
    }

    @Test
    void theSkipListsComeFromTheOptions() {
        var policy = ToolPermissionPolicy.builder(ws.root()).build();
        var custom = ToolRegistry.builder()
                .register(new GrepTool(policy, new SearchOptions(Set.of("src"), List.of()))).build();
        var result = custom.execute(new ToolUse("id", "grep",
                JSON.readTree("{\"pattern\":\"zebra\",\"outputMode\":\"files\"}")), context);
        assertFalse(result.output().contains("src/A.java"), result.output());
        assertTrue(result.output().contains("target/x.txt") && result.output().contains(".idea/a.xml"), result.output());
    }

    @Test
    void oneGrepOverManyFilesWritesOneAuditEntry() throws Exception {
        for (var i = 0; i < 500; i++) ws.write("src/gen/F" + i + ".txt", "zebra " + i);
        Files.deleteIfExists(audit.file());
        var result = call("grep", "{\"pattern\":\"zebra\",\"outputMode\":\"count\",\"limit\":1000}");
        assertFalse(result.error(), result.output());
        var lines = Files.readAllLines(audit.file());
        assertEquals(1, lines.size(), String.join("\n", lines.subList(0, Math.min(3, lines.size()))));
        assertTrue(lines.getFirst().contains("\"tool\":\"grep\"") && lines.getFirst().contains("\"decision\":\"ALLOW\""));
    }

    @Test
    void aRefusedFileInASearchIsStillAuditedAndSkipped() throws Exception {
        ws.write("secrets/key.txt", "zebra secret");
        var policy = ToolPermissionPolicy.builder(ws.root()).audit(audit)
                .rules(List.of(new org.github.lscoughlin.jeorje.core.permissions.Rule("grep", ws.root() + "/secrets/*",
                    org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision.DENY))).build();
        var guarded = ToolRegistry.builder().register(new GrepTool(policy)).build();
        Files.deleteIfExists(audit.file());
        var result = guarded.execute(new ToolUse("id", "grep",
                JSON.readTree("{\"pattern\":\"zebra\",\"outputMode\":\"files\"}")), context);
        assertTrue(result.output().contains("src/A.java"), result.output());
        assertFalse(result.output().contains("secrets/key.txt"), result.output());
        assertTrue(result.output().contains("skipped by the permission policy"), result.output());
        var audited = String.join("\n", Files.readAllLines(audit.file()));
        assertTrue(audited.contains("\"decision\":\"DENY\"") || audited.contains("\"decision\":\"ASK\""), audited);
    }
}
