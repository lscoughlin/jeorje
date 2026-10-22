package org.github.lscoughlin.jeorje.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectMcpFileTest {

    @TempDir Path tmp;

    private void file(String text) throws IOException {
        Files.writeString(tmp.resolve(ProjectMcpFile.FILE_NAME), text);
    }

    @Test
    void readsStdioAndHttpEntriesInFileOrder() throws IOException {
        file("""
                {"mcpServers": {
                  "clarity": {"type": "stdio", "command": "clarity", "args": ["mcp"], "env": {"A": "b"}},
                  "remote": {"type": "http", "url": "http://127.0.0.1:9000/mcp", "headers": {"X": "y"}}}}
                """);
        var warnings = new ArrayList<String>();
        var servers = ProjectMcpFile.read(tmp, Map.of(), warnings::add);
        assertEquals(List.of("clarity", "remote"), List.copyOf(servers.keySet()));
        var stdio = servers.get("clarity");
        assertEquals("stdio", stdio.transport());
        assertEquals("clarity", stdio.command());
        assertEquals(List.of("mcp"), stdio.args());
        assertEquals(Map.of("A", "b"), stdio.env());
        var http = servers.get("remote");
        assertEquals("http", http.transport());
        assertEquals("http://127.0.0.1:9000/mcp", http.url());
        assertEquals(Map.of("X", "y"), http.headers());
        assertEquals(60, http.timeoutSeconds());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void aMissingFileGivesNoServersAndNoWarning() {
        var warnings = new ArrayList<String>();
        assertTrue(ProjectMcpFile.read(tmp, Map.of(), warnings::add).isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void brokenJsonGivesOneWarning() throws IOException {
        file("{ not json");
        var warnings = new ArrayList<String>();
        assertTrue(ProjectMcpFile.read(tmp, Map.of(), warnings::add).isEmpty());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains(ProjectMcpFile.FILE_NAME));
    }

    @Test
    void aBadTopLevelGivesOneWarning() throws IOException {
        for (var text : List.of("[]", "{\"mcpServers\": []}")) {
            file(text);
            var warnings = new ArrayList<String>();
            assertTrue(ProjectMcpFile.read(tmp, Map.of(), warnings::add).isEmpty(), text);
            assertEquals(1, warnings.size(), text);
        }
    }

    @Test
    void badEntriesAreSkippedAndTheValidOneStays() throws IOException {
        file("""
                {"mcpServers": {
                  "events": {"type": "sse", "url": "http://x/sse"},
                  "Bad Name": {"command": "x"},
                  "empty": {"type": "stdio"},
                  "good": {"command": "tool"}}}
                """);
        var warnings = new ArrayList<String>();
        var servers = ProjectMcpFile.read(tmp, Map.of(), warnings::add);
        assertEquals(List.of("good"), List.copyOf(servers.keySet()));
        assertEquals("stdio", servers.get("good").transport());
        assertEquals(3, warnings.size());
        assertTrue(warnings.get(0).contains("events"));
        assertTrue(warnings.get(1).contains("Bad Name"));
        assertTrue(warnings.get(2).contains("empty"));
    }

    @Test
    void placeholdersComeFromTheGivenEnvironment() throws IOException {
        file("""
                {"mcpServers": {
                  "a": {"command": "${BIN}/tool", "args": ["--k=${KEY}", "${MISSING}x"], "env": {"${KEY}": "${KEY}"}},
                  "b": {"url": "${BASE}/mcp", "headers": {"Authorization": "Bearer ${KEY}"}}}}
                """);
        var servers = ProjectMcpFile.read(tmp, Map.of("BIN", "/opt/bin", "KEY", "k1", "BASE", "http://h"),
                m -> {
                });
        var a = servers.get("a");
        assertEquals("/opt/bin/tool", a.command());
        assertEquals(List.of("--k=k1", "x"), a.args());
        assertEquals(Map.of("${KEY}", "k1"), a.env());
        var b = servers.get("b");
        assertEquals("http://h/mcp", b.url());
        assertEquals(Map.of("Authorization", "Bearer k1"), b.headers());
    }

    @Test
    void aRelativeCwdStartsAtTheDirectory() throws IOException {
        file("{\"mcpServers\": {\"a\": {\"command\": \"x\", \"cwd\": \"sub\"}}}");
        var servers = ProjectMcpFile.read(tmp, Map.of(), m -> {
        });
        assertEquals(tmp.resolve("sub").toAbsolutePath().toString(), servers.get("a").cwd());
    }
}
