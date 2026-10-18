package org.github.lscoughlin.jeorje.tools.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.permissions.ToolPermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.state.AgentState;
import org.github.lscoughlin.jeorje.testkit.TempWorkspace;
import org.github.lscoughlin.jeorje.tools.files.ReadTool;
import org.github.lscoughlin.jeorje.tools.registry.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DiffPatchToolsTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    TempWorkspace ws;
    AgentState state;
    ToolRegistry registry;
    ToolContext context;

    @BeforeEach
    void setUp() {
        ws = new TempWorkspace();
        state = new AgentState();
        registry = build(null);
        context = new ToolContext(ws.root(), new AgentId("a"), CancellationToken.none(), null, Clock.systemUTC());
    }

    ToolRegistry build(PatchTool.FileOps ops) {
        var policy = ToolPermissionPolicy.builder(ws.root()).build();
        var patch = ops == null ? new PatchTool(policy, state) : new PatchTool(policy, state, 2, ops);
        return ToolRegistry.builder().register(new ReadTool(policy, state))
                .register(new DiffTool(policy)).register(patch).build();
    }

    @AfterEach
    void tearDown() { ws.close(); }

    ToolResultData call(String tool, String json) {
        var result = registry.execute(new ToolUse("id", tool, JSON.readTree(json)), context);
        state.apply(result.updates());
        return result;
    }

    static String q(String s) { return JSON.writeValueAsString(s); }

    ToolResultData patch(String diff, String extra) {
        return call("patch", "{\"diff\":" + q(diff) + extra + "}");
    }

    void read(String path) { call("read", "{\"path\":\"" + path + "\"}"); }

    static final String ONE = "--- a/f.txt\n+++ b/f.txt\n@@ -1,3 +1,3 @@\n one\n-two\n+TWO\n three\n";

    // diff

    @Test
    void diffComparesFilesAndText() {
        ws.write("a.txt", "one\ntwo\nthree\n");
        ws.write("b.txt", "one\n2\nthree\n");
        var r = call("diff", "{\"a\":\"a.txt\",\"b\":\"b.txt\"}");
        assertFalse(r.error());
        assertTrue(r.output().contains("--- a/a.txt\n+++ b/b.txt"), r.output());
        assertTrue(r.output().contains("-two\n+2"));
        assertTrue(r.output().startsWith("+1 -1"));
        var same = call("diff", "{\"a\":\"a.txt\",\"bContent\":\"one\\ntwo\\nthree\\n\"}");
        assertTrue(same.output().contains("no differences"));
        var other = call("diff", "{\"a\":\"a.txt\",\"bContent\":\"one\\nzwei\\nthree\\n\"}");
        assertTrue(other.output().contains("+zwei"));
    }

    @Test
    void diffOptions() {
        ws.write("a.txt", "x \ny\n1\n2\n3\n4\n5\n6\n7\n8\nz\n");
        ws.write("b.txt", "x\ny\n1\n2\n3\n4\n5\n6\n7\n8\nZ\n");
        assertTrue(call("diff", "{\"a\":\"a.txt\",\"b\":\"b.txt\"}").output().contains("-x "));
        var ws1 = call("diff", "{\"a\":\"a.txt\",\"b\":\"b.txt\",\"ignoreWhitespace\":\"trailing\",\"context\":0}");
        assertFalse(ws1.output().contains("-x "));
        assertTrue(ws1.output().contains("+Z"));
        assertTrue(call("diff", "{\"a\":\"a.txt\",\"b\":\"b.txt\",\"algorithm\":\"myers-linear\"}").output().contains("+Z"));
        var inline = call("diff", "{\"a\":\"a.txt\",\"b\":\"b.txt\",\"format\":\"inline\"}");
        assertFalse(inline.error());
        assertTrue(inline.output().contains("Z"));
        assertTrue(call("diff", "{\"a\":\"a.txt\",\"b\":\"b.txt\",\"format\":\"x\"}").error());
        assertTrue(call("diff", "{\"a\":\"a.txt\"}").error());
        assertTrue(call("diff", "{\"a\":\"nope\",\"b\":\"b.txt\"}").error());
    }

    @Test
    void diffRespectsSandboxAndIsReadOnly() {
        assertTrue(call("diff", "{\"a\":\"../../../etc/hosts\",\"bContent\":\"x\"}").error());
        var tool = new DiffTool(null);
        assertTrue(tool.readOnly());
    }

    // patch

    @Test
    void patchSingleFileAndStaleCheck() {
        ws.write("f.txt", "one\ntwo\nthree\n");
        var unread = patch(ONE, "");
        assertTrue(unread.error());
        assertTrue(unread.output().contains("did not read it"));
        assertEquals("one\ntwo\nthree\n", ws.read("f.txt"));
        read("f.txt");
        ws.write("f.txt", "one\ntwo\nthree\nfour\n");
        var stale = patch(ONE, "");
        assertTrue(stale.error());
        assertTrue(stale.output().contains("changed on disk"));
        read("f.txt");
        var ok = patch(ONE, "");
        assertFalse(ok.error(), ok.output());
        assertEquals("one\nTWO\nthree\nfour\n", ws.read("f.txt"));
        assertTrue(ok.output().contains("modified f.txt"));
        assertTrue(ok.output().contains("offset 0"));
        // the tool recorded the new state, so a second patch needs no read
        var again = patch(ONE.replace("one\n-two\n+TWO", "one\n-TWO\n+two"), "");
        assertFalse(again.error(), again.output());
        assertEquals("one\ntwo\nthree\nfour\n", ws.read("f.txt"));
    }

    @Test
    void patchMultiFileWithStripCreateAndDelete() {
        ws.write("src/a.txt", "a1\na2\n");
        ws.write("old.txt", "gone\n");
        read("src/a.txt");
        read("old.txt");
        var diff = "--- a/src/a.txt\n+++ b/src/a.txt\n@@ -1,2 +1,2 @@\n a1\n-a2\n+A2\n"
                + "--- /dev/null\n+++ b/new/n.txt\n@@ -0,0 +1,2 @@\n+n1\n+n2\n"
                + "--- a/old.txt\n+++ /dev/null\n@@ -1 +0,0 @@\n-gone\n";
        var r = patch(diff, "");
        assertFalse(r.error(), r.output());
        assertEquals("a1\nA2\n", ws.read("src/a.txt"));
        assertEquals("n1\nn2\n", ws.read("new/n.txt"));
        assertFalse(ws.exists("old.txt"));
        assertTrue(r.output().contains("created new/n.txt"));
        assertTrue(r.output().contains("deleted old.txt"));
        assertTrue(r.output().contains("modified src/a.txt"));
    }

    @Test
    void patchStripNumber() {
        ws.write("x/f.txt", "one\ntwo\nthree\n");
        read("x/f.txt");
        var diff = ONE.replace("a/f.txt", "p/q/x/f.txt").replace("b/f.txt", "p/q/x/f.txt");
        var r = patch(diff, ",\"strip\":\"2\"");
        assertFalse(r.error(), r.output());
        assertEquals("one\nTWO\nthree\n", ws.read("x/f.txt"));
    }

    @Test
    void patchCreateNeedsNoExistingFile() {
        ws.write("n.txt", "x\n");
        var diff = "--- /dev/null\n+++ b/n.txt\n@@ -0,0 +1 @@\n+new\n";
        var r = patch(diff, "");
        assertTrue(r.error());
        assertTrue(r.output().contains("already exists"));
        assertEquals("x\n", ws.read("n.txt"));
    }

    @Test
    void patchMovedHunkReportsOffset() {
        ws.write("f.txt", "p1\np2\np3\none\ntwo\nthree\n");
        read("f.txt");
        var r = patch(ONE, "");
        assertFalse(r.error(), r.output());
        assertEquals("p1\np2\np3\none\nTWO\nthree\n", ws.read("f.txt"));
        assertTrue(r.output().contains("offset 3"), r.output());
    }

    @Test
    void patchFuzz() {
        ws.write("f.txt", "alpha\ntwo\nomega\n");
        read("f.txt");
        var strict = patch(ONE, ",\"fuzz\":0");
        assertTrue(strict.error());
        assertEquals("alpha\ntwo\nomega\n", ws.read("f.txt"));
        var fuzzy = patch(ONE, ",\"fuzz\":1");
        assertFalse(fuzzy.error(), fuzzy.output());
        assertEquals("alpha\nTWO\nomega\n", ws.read("f.txt"));
        assertTrue(fuzzy.output().contains("fuzz 1"), fuzzy.output());
        assertTrue(patch(ONE, ",\"fuzz\":9").error());
    }

    @Test
    void patchReverse() {
        ws.write("f.txt", "one\nTWO\nthree\n");
        read("f.txt");
        var r = patch(ONE, ",\"reverse\":true");
        assertFalse(r.error(), r.output());
        assertEquals("one\ntwo\nthree\n", ws.read("f.txt"));
    }

    @Test
    void patchDryRunWritesNothing() {
        ws.write("f.txt", "one\ntwo\nthree\n");
        read("f.txt");
        var r = patch(ONE, ",\"dryRun\":true");
        assertFalse(r.error(), r.output());
        assertTrue(r.output().contains("Dry run"));
        assertTrue(r.output().contains("+TWO"));
        assertEquals("one\ntwo\nthree\n", ws.read("f.txt"));
    }

    @Test
    void patchIsAllOrNothingAndReportsNearbyLines() {
        ws.write("f.txt", "one\ntwo\nthree\n");
        ws.write("g.txt", "q\nr\n");
        read("f.txt");
        read("g.txt");
        var diff = ONE + "--- a/g.txt\n+++ b/g.txt\n@@ -1,2 +1,2 @@\n q\n-WRONG\n+s\n";
        var r = patch(diff, "");
        assertTrue(r.error());
        assertTrue(r.output().contains("No file changed"));
        assertTrue(r.output().contains("g.txt"));
        assertTrue(r.output().contains("hunk 1"));
        assertTrue(r.output().contains("1: q"), r.output());
        assertEquals("one\ntwo\nthree\n", ws.read("f.txt"));
        assertEquals("q\nr\n", ws.read("g.txt"));
    }

    @Test
    void patchConflictMarkers() {
        ws.write("f.txt", "one\nchanged\nthree\n");
        ws.write("g.txt", "k\nl\n");
        read("f.txt");
        read("g.txt");
        var diff = "--- a/f.txt\n+++ b/f.txt\n@@ -1,3 +1,3 @@\n one\n-two\n+TWO\n three\n"
                + "--- a/g.txt\n+++ b/g.txt\n@@ -1,2 +1,2 @@\n k\n-l\n+L\n";
        var r = patch(diff, ",\"conflicts\":\"markers\",\"fuzz\":0");
        assertFalse(r.error(), r.output());
        assertTrue(r.output().contains("conflicted"), r.output());
        assertTrue(ws.read("f.txt").contains("<<<<<<<"), ws.read("f.txt"));
        assertEquals("k\nL\n", ws.read("g.txt"));
    }

    @Test
    void patchRestoresAfterWriteFailure() throws Exception {
        ws.write("a.txt", "a\nb\n");
        ws.write("c.txt", "c\nd\n");
        ws.write("e.txt", "e\nf\n");
        read("a.txt");
        read("c.txt");
        read("e.txt");
        var counter = new AtomicInteger();
        var failing = new PatchTool.FileOps() {
            @Override
            public void write(Path file, String content) throws IOException {
                if (counter.incrementAndGet() == 3) throw new IOException("disk full");
                Files.writeString(file, content);
            }

            @Override
            public void delete(Path file) throws IOException { Files.delete(file); }
        };
        registry = build(failing);
        var diff = "--- a/a.txt\n+++ b/a.txt\n@@ -1,2 +1,2 @@\n a\n-b\n+B\n"
                + "--- a/c.txt\n+++ b/c.txt\n@@ -1,2 +1,2 @@\n c\n-d\n+D\n"
                + "--- a/e.txt\n+++ b/e.txt\n@@ -1,2 +1,2 @@\n e\n-f\n+F\n";
        var r = patch(diff, "");
        assertTrue(r.error());
        assertTrue(r.output().contains("disk full"));
        assertEquals("a\nb\n", ws.read("a.txt"));
        assertEquals("c\nd\n", ws.read("c.txt"));
        assertEquals("e\nf\n", ws.read("e.txt"));
    }

    @Test
    void patchRefusesOutsideWorkspaceAndGit() {
        ws.write(".git/config", "x\n");
        read(".git/config");
        var git = patch("--- a/.git/config\n+++ b/.git/config\n@@ -1 +1 @@\n-x\n+y\n", "");
        assertTrue(git.error());
        assertTrue(git.output().contains(".git"));
        assertEquals("x\n", ws.read(".git/config"));
        var up = patch("--- /dev/null\n+++ b/../../evil.txt\n@@ -0,0 +1 @@\n+x\n", "");
        assertTrue(up.error());
        var abs = patch("--- /dev/null\n+++ /tmp/jeorje-evil-file.txt\n@@ -0,0 +1 @@\n+x\n", "");
        assertTrue(abs.error());
        assertFalse(Files.exists(Path.of("/tmp/jeorje-evil-file.txt")));
    }

    @Test
    void patchReadsDiffFileAndValidatesInput() {
        ws.write("f.txt", "one\ntwo\nthree\n");
        ws.write("p.diff", ONE);
        read("f.txt");
        assertTrue(call("patch", "{}").error());
        assertTrue(call("patch", "{\"diff\":\"x\",\"diffFile\":\"p.diff\"}").error());
        assertTrue(patch("not a diff", "").error());
        var r = call("patch", "{\"diffFile\":\"p.diff\"}");
        assertFalse(r.error(), r.output());
        assertEquals("one\nTWO\nthree\n", ws.read("f.txt"));
    }

    // configured defaults (tools.diff.*, tools.patch.*)

    ToolRegistry buildConfigured(int fuzz, String conflicts, int diffContext, String algorithm) {
        var policy = ToolPermissionPolicy.builder(ws.root()).build();
        var diffDefaults = org.github.lscoughlin.jeorje.core.diff.TextDiff.Options.defaults().withContext(diffContext);
        return ToolRegistry.builder().register(new ReadTool(policy, state))
                .register(new DiffTool(policy, diffDefaults, algorithm))
                .register(new PatchTool(policy, state, fuzz, conflicts, PatchTool.defaultOps())).build();
    }

    @Test
    void configuredDiffContextIsTheDefaultAndTheCallOverridesIt() {
        ws.write("a.txt", "1\n2\n3\n4\n5\n6\n7\n");
        registry = buildConfigured(2, "fail", 0, "myers");
        var none = call("diff", "{\"a\":\"a.txt\",\"bContent\":\"1\\n2\\n3\\nX\\n5\\n6\\n7\\n\"}");
        assertFalse(none.error(), none.output());
        assertFalse(none.output().contains(" 3\n"), none.output());
        var wide = call("diff", "{\"a\":\"a.txt\",\"context\":2,\"bContent\":\"1\\n2\\n3\\nX\\n5\\n6\\n7\\n\"}");
        assertTrue(wide.output().contains(" 3\n"), wide.output());
    }

    @Test
    void configuredDiffAlgorithmIsTheDefault() {
        ws.write("a.txt", "one\ntwo\n");
        registry = buildConfigured(2, "fail", 3, "myers-linear");
        assertFalse(call("diff", "{\"a\":\"a.txt\",\"bContent\":\"one\\nTWO\\n\"}").error());
        registry = buildConfigured(2, "fail", 3, "bogus");
        assertTrue(call("diff", "{\"a\":\"a.txt\",\"bContent\":\"one\\nTWO\\n\"}").error());
        assertFalse(call("diff", "{\"a\":\"a.txt\",\"algorithm\":\"myers\",\"bContent\":\"one\\nTWO\\n\"}").error());
    }

    @Test
    void configuredPatchFuzzIsTheDefault() {
        ws.write("f.txt", "alpha\ntwo\nomega\n");
        registry = buildConfigured(0, "fail", 3, "myers");
        read("f.txt");
        assertTrue(patch(ONE, "").error());
        registry = buildConfigured(1, "fail", 3, "myers");
        var fuzzy = patch(ONE, "");
        assertFalse(fuzzy.error(), fuzzy.output());
        assertEquals("alpha\nTWO\nomega\n", ws.read("f.txt"));
    }

    @Test
    void configuredPatchConflictsIsTheDefault() {
        ws.write("f.txt", "one\nchanged\nthree\n");
        registry = buildConfigured(0, "markers", 3, "myers");
        read("f.txt");
        var r = patch(ONE, "");
        assertFalse(r.error(), r.output());
        assertTrue(ws.read("f.txt").contains("<<<<<<<"), ws.read("f.txt"));
        ws.write("f.txt", "one\nchanged\nthree\n");
        registry = buildConfigured(0, "fail", 3, "myers");
        read("f.txt");
        assertTrue(patch(ONE, "").error());
        assertFalse(patch(ONE, ",\"conflicts\":\"markers\"").error());
    }
}
