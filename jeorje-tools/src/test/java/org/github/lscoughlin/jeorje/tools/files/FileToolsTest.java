package org.github.lscoughlin.jeorje.tools.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.permissions.ToolPermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.state.AgentState;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.github.lscoughlin.jeorje.tools.registry.OutputLimiter;
import org.github.lscoughlin.jeorje.tools.registry.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FileToolsTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    TempWorkspace ws;
    AgentState state;
    ToolRegistry registry;
    ToolContext context;

    @BeforeEach
    void setUp() {
        ws = new TempWorkspace();
        state = new AgentState();
        var policy = ToolPermissionPolicy.builder(ws.root()).build();
        registry = ToolRegistry.builder().register(new ReadTool(policy, state))
                .register(new WriteTool(policy, state)).register(new EditTool(policy, state)).build();
        context = new ToolContext(ws.root(), new AgentId("a"), CancellationToken.none(), null, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() { ws.close(); }

    /** Calls a tool and applies the updates, as the agent loop does. */
    ToolResultData call(String tool, String json) {
        var result = registry.execute(new ToolUse("id", tool, JSON.readTree(json)), context);
        state.apply(result.updates());
        return result;
    }

    static String q(String s) { return JSON.writeValueAsString(s); }

    // read

    @Test
    void readNumbersLinesWithOffsetAndLimit() {
        ws.write("a.txt", "one\ntwo\nthree\nfour\n");
        var all = call("read", "{\"path\":\"a.txt\"}");
        assertEquals("     1\tone\n     2\ttwo\n     3\tthree\n     4\tfour\n", all.output());
        var part = call("read", "{\"path\":\"a.txt\",\"offset\":2,\"limit\":2}");
        assertEquals("     2\ttwo\n     3\tthree\n[1 more lines. Use offset 4 to continue.]\n", part.output());
        assertTrue(call("read", "{\"path\":\"a.txt\",\"offset\":9}").output().contains("past the end"));
    }

    @Test
    void readRefusesMissingDirectoryAndBinary() throws Exception {
        assertTrue(call("read", "{\"path\":\"nope\"}").error());
        ws.mkdir("d");
        assertTrue(call("read", "{\"path\":\"d\"}").error());
        Files.write(ws.resolve("b.bin"), new byte[] {1, 0, 2});
        assertTrue(call("read", "{\"path\":\"b.bin\"}").error());
    }

    @Test
    void readReturnsFrontMatterOnlyFromLineOne() {
        ws.write("A.java", "// ---\n// title: Demo\n// ---\nclass A {}\n");
        var first = call("read", "{\"path\":\"A.java\"}").output();
        assertTrue(first.startsWith("[front matter]\ntitle: Demo\n\n"), first);
        assertTrue(first.contains("     1\t// ---"), "front matter stays in the numbered lines");
        assertFalse(call("read", "{\"path\":\"A.java\",\"offset\":2}").output().contains("[front matter]"));
    }

    @Test
    void readSkipsFrontMatterInDataFiles() {
        ws.write("a.yaml", "---\ntitle: x\n---\n");
        ws.write("a.yml", "---\ntitle: x\n---\n");
        ws.write("a.json", "{}\n");
        for (var name : new String[] {"a.yaml", "a.yml", "a.json"}) {
            assertFalse(call("read", "{\"path\":\"" + name + "\"}").output().contains("[front matter]"), name);
        }
    }

    @Test
    void directoryContextOrderAndOneTimeDelivery() {
        ws.write("AGENTS.md", "root agents");
        ws.write("README.md", "root readme\nline2");
        ws.write("sub/AGENTS.md", "sub agents");
        ws.write("sub/llms.txt", "---\ntitle: Sub llms\n---\nbody that is not shown");
        ws.write("sub/llm.txt", "llm text");
        ws.write("sub/deep/README.md", "deep readme");
        ws.write("sub/deep/f1.txt", "x");
        ws.write("sub/deep/f2.txt", "y");
        var out = call("read", "{\"path\":\"sub/deep/f1.txt\"}").output();
        var order = new String[] {"[directory context: README.md]", "[directory context: sub/AGENTS.md]",
                "[directory context: sub/llms.txt]", "[directory context: sub/llm.txt]",
                "[directory context: sub/deep/README.md]", "     1\tx"};
        var last = -1;
        for (var marker : order) {
            var at = out.indexOf(marker);
            assertTrue(at > last, marker + " in\n" + out);
            last = at;
        }
        assertFalse(out.contains("root agents"), "root AGENTS.md is in the system prompt");
        assertTrue(out.contains("title: Sub llms"));
        assertFalse(out.contains("body that is not shown"));
        // Second read in the same directory: no repeat. A sibling directory chain gives only new directories.
        assertEquals("     1\ty\n", call("read", "{\"path\":\"sub/deep/f2.txt\"}").output());
        ws.write("sub/other.txt", "z");
        assertEquals("     1\tz\n", call("read", "{\"path\":\"sub/other.txt\"}").output());
        // Forget a directory: the context comes again.
        state.forgetDirectory(ws.resolve("sub/deep"));
        assertTrue(call("read", "{\"path\":\"sub/deep/f2.txt\"}").output().contains("deep readme"));
    }

    @Test
    void directoryContextFallbackLinesAndCharCut() {
        var many = new StringBuilder();
        for (var i = 1; i <= 30; i++) many.append("row").append(i).append('\n');
        ws.write("d/README.md", many.toString());
        ws.write("d/f.txt", "x");
        var out = call("read", "{\"path\":\"d/f.txt\"}").output();
        assertTrue(out.contains("row20"));
        assertFalse(out.contains("row21"));
        ws.write("e/README.md", "x".repeat(5000));
        ws.write("e/f.txt", "x");
        var cut = call("read", "{\"path\":\"e/f.txt\"}").output();
        assertTrue(cut.length() < 2300, "length " + cut.length());
    }

    @Test
    void readDoesNotWriteSharedStateItReturnsUpdates() {
        ws.write("README.md", "r");
        ws.write("d/README.md", "r");
        ws.write("d/f.txt", "x");
        var result = registry.execute(new ToolUse("id", "read", JSON.readTree("{\"path\":\"d/f.txt\"}")), context);
        assertEquals(0, state.files().size());
        assertEquals(0, state.directories().size());
        assertEquals(3, result.updates().size());
        state.apply(result.updates());
        assertEquals(1, state.files().size());
        assertEquals(2, state.directories().size());
    }

    @Test
    void updatesSurviveOutputLimiter() {
        var limited = new OutputLimiter(10).apply(ToolResultData.ok("x".repeat(100), java.util.List.of(
                new org.github.lscoughlin.jeorje.core.state.StateUpdate.DirectoryDelivered(ws.root()))));
        assertEquals(1, limited.updates().size());
    }

    // write

    @Test
    void writeCreatesFileAndParentsWithDiff() {
        var result = call("write", "{\"path\":\"new/dir/f.txt\",\"content\":\"hi\\nthere\\n\"}");
        assertFalse(result.error(), result.output());
        assertEquals("hi\nthere\n", ws.read("new/dir/f.txt"));
        assertTrue(result.output().contains("--- /dev/null"));
        assertTrue(result.output().contains("+hi"));
        // The new file counts as read: a further write is allowed.
        assertFalse(call("write", "{\"path\":\"new/dir/f.txt\",\"content\":\"x\\n\"}").error());
    }

    @Test
    void writeRefusesExistingFileThatWasNotRead() {
        ws.write("f.txt", "old\n");
        var result = call("write", "{\"path\":\"f.txt\",\"content\":\"new\\n\"}");
        assertTrue(result.error());
        assertTrue(result.output().contains("Read it"));
        assertEquals("old\n", ws.read("f.txt"));
    }

    @Test
    void staleWriteAndEditAreRefused() throws Exception {
        ws.write("f.txt", "old\n");
        call("read", "{\"path\":\"f.txt\"}");
        Files.writeString(ws.resolve("f.txt"), "changed\n");
        Files.setLastModifiedTime(ws.resolve("f.txt"), FileTime.fromMillis(System.currentTimeMillis() + 5000));
        var write = call("write", "{\"path\":\"f.txt\",\"content\":\"new\\n\"}");
        assertTrue(write.error());
        assertTrue(write.output().contains("changed on disk"));
        var edit = call("edit", "{\"path\":\"f.txt\",\"oldString\":\"changed\",\"newString\":\"z\"}");
        assertTrue(edit.error());
        assertEquals("changed\n", ws.read("f.txt"));
        call("read", "{\"path\":\"f.txt\"}");
        assertFalse(call("edit", "{\"path\":\"f.txt\",\"oldString\":\"changed\",\"newString\":\"z\"}").error());
    }

    @Test
    void sameContentWithNewModificationTimeIsNotStale() throws Exception {
        ws.write("f.txt", "same\n");
        call("read", "{\"path\":\"f.txt\"}");
        Files.setLastModifiedTime(ws.resolve("f.txt"), FileTime.fromMillis(System.currentTimeMillis() + 9000));
        assertFalse(call("write", "{\"path\":\"f.txt\",\"content\":\"other\\n\"}").error());
    }

    @Test
    void writeIsAtomicAndLeavesNoTemporaryFile() throws Exception {
        ws.write("f.txt", "old\n");
        call("read", "{\"path\":\"f.txt\"}");
        assertFalse(call("write", "{\"path\":\"f.txt\",\"content\":\"new\\n\"}").error());
        assertEquals("new\n", ws.read("f.txt"));
        try (var list = Files.list(ws.root())) {
            assertEquals(1, list.count(), "only f.txt remains");
        }
    }

    @Test
    void failedWriteKeepsTheOriginalAndLeavesNoTemporaryFile() throws Exception {
        ws.write("f.txt", "old\n");
        call("read", "{\"path\":\"f.txt\"}");
        Files.delete(ws.resolve("f.txt"));
        Files.createDirectory(ws.resolve("f.txt"));
        var result = call("write", "{\"path\":\"f.txt\",\"content\":\"new\\n\"}");
        assertTrue(result.error());
        try (var list = Files.list(ws.root())) {
            assertEquals(1, list.count());
        }
    }

    @Test
    void writeKeepsCrlfOfAnExistingFile() throws Exception {
        ws.write("f.txt", "a\r\nb\r\n");
        call("read", "{\"path\":\"f.txt\"}");
        call("write", "{\"path\":\"f.txt\",\"content\":\"a\\nc\\n\"}");
        assertEquals("a\r\nc\r\n", Files.readString(ws.resolve("f.txt")));
    }

    // edit

    @Test
    void editReplacesUniqueStringAndReturnsDiff() {
        ws.write("f.txt", "alpha\nbeta\ngamma\n");
        call("read", "{\"path\":\"f.txt\"}");
        var result = call("edit", "{\"path\":\"f.txt\",\"oldString\":\"beta\",\"newString\":\"BETA\"}");
        assertFalse(result.error(), result.output());
        assertEquals("alpha\nBETA\ngamma\n", ws.read("f.txt"));
        assertTrue(result.output().contains("-beta"));
        assertTrue(result.output().contains("+BETA"));
        // The edit updated the file state: a second edit works without a new read.
        assertFalse(call("edit", "{\"path\":\"f.txt\",\"oldString\":\"gamma\",\"newString\":\"G\"}").error());
    }

    @Test
    void editRequiresUniqueMatchUnlessReplaceAll() {
        ws.write("f.txt", "x\nx\ny\n");
        call("read", "{\"path\":\"f.txt\"}");
        var many = call("edit", "{\"path\":\"f.txt\",\"oldString\":\"x\",\"newString\":\"z\"}");
        assertTrue(many.error());
        assertTrue(many.output().contains("2 places"));
        var none = call("edit", "{\"path\":\"f.txt\",\"oldString\":\"q\",\"newString\":\"z\"}");
        assertTrue(none.error());
        assertTrue(none.output().contains("not found"));
        assertEquals("x\nx\ny\n", ws.read("f.txt"));
        var all = call("edit", "{\"path\":\"f.txt\",\"oldString\":\"x\",\"newString\":\"z\",\"replaceAll\":true}");
        assertFalse(all.error(), all.output());
        assertEquals("z\nz\ny\n", ws.read("f.txt"));
    }

    @Test
    void editRefusesUnreadFileEmptyAndSameStrings() {
        ws.write("f.txt", "x\n");
        assertTrue(call("edit", "{\"path\":\"f.txt\",\"oldString\":\"x\",\"newString\":\"y\"}").error());
        call("read", "{\"path\":\"f.txt\"}");
        assertTrue(call("edit", "{\"path\":\"f.txt\",\"oldString\":\"\",\"newString\":\"y\"}").error());
        assertTrue(call("edit", "{\"path\":\"f.txt\",\"oldString\":\"x\",\"newString\":\"x\"}").error());
        assertTrue(call("edit", "{\"path\":\"missing\",\"oldString\":\"x\",\"newString\":\"y\"}").error());
    }

    @Test
    void editPreservesCrlfAndFinalNewlineState() throws Exception {
        Files.write(ws.resolve("c.txt"), "one\r\ntwo\r\nthree".getBytes(StandardCharsets.UTF_8));
        call("read", "{\"path\":\"c.txt\"}");
        var result = call("edit", "{\"path\":\"c.txt\",\"oldString\":\"two\\nthree\",\"newString\":\"2\\n3\\n4\"}");
        assertFalse(result.error(), result.output());
        assertEquals("one\r\n2\r\n3\r\n4", Files.readString(ws.resolve("c.txt")));
    }

    @Test
    void editKeepsMixedLineEndsOfUntouchedLines() throws Exception {
        Files.write(ws.resolve("m.txt"), "a\r\nb\nc\r\nd\n".getBytes(StandardCharsets.UTF_8));
        call("read", "{\"path\":\"m.txt\"}");
        assertFalse(call("edit", "{\"path\":\"m.txt\",\"oldString\":\"c\",\"newString\":\"C\"}").error());
        assertEquals("a\r\nb\nC\r\nd\n", Files.readString(ws.resolve("m.txt")));
    }

    @Test
    void editKeepsMissingFinalNewline() throws Exception {
        Files.write(ws.resolve("n.txt"), "a\nb".getBytes(StandardCharsets.UTF_8));
        call("read", "{\"path\":\"n.txt\"}");
        assertFalse(call("edit", "{\"path\":\"n.txt\",\"oldString\":\"a\",\"newString\":\"A\"}").error());
        assertEquals("A\nb", Files.readString(ws.resolve("n.txt")));
    }

    @Test
    void sandboxDeniedPathIsRefused() {
        var result = call("read", "{\"path\":\"../../etc/passwd\"}");
        assertTrue(result.error(), result.output());
    }
}
