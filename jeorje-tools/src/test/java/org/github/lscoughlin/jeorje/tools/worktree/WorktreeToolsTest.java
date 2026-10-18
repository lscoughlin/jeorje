package org.github.lscoughlin.jeorje.tools.worktree;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.subagent.SpawnMode;
import org.github.lscoughlin.jeorje.core.worktree.GitRunner;
import org.github.lscoughlin.jeorje.core.worktree.Worktree;
import org.github.lscoughlin.jeorje.core.worktree.WorktreeCompletion;
import org.github.lscoughlin.jeorje.core.worktree.WorktreeManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class WorktreeToolsTest {
    private static final GitRunner GIT = GitRunner.process();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir Path tmp;
    private Path root;
    private WorktreeManager manager;
    private WorktreeCommands commands;
    private final List<String> notes = new ArrayList<>();

    private static String g(Path dir, String... args) {
        var r = GIT.run(dir, args);
        assertTrue(r.ok(), String.join(" ", args) + ": " + r.text());
        return r.trimmed();
    }

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tmp.resolve("repo"));
        g(root, "init", "-q", "-b", "main");
        g(root, "config", "user.name", "Tester");
        g(root, "config", "user.email", "t@example.com");
        g(root, "config", "commit.gpgsign", "false");
        Files.writeString(root.resolve("a.txt"), "one\ntwo\nthree\n");
        g(root, "add", "-A");
        g(root, "commit", "-q", "-m", "initial");
        var cfg = new JeorjeConfig.Worktree("auto", "head", "jeorje", List.of(), List.of(), "delete-if-clean", "merge",
            "auto", 300, new JeorjeConfig.Sync(3, 30, ""));
        manager = new WorktreeManager(GIT, root, cfg, s -> {});
        var completion = new WorktreeCompletion(manager, (d, c) -> new org.github.lscoughlin.jeorje.core.worktree.Verifier.Result(true, ""), null);
        commands = new WorktreeCommands(manager, completion, (agent, note) -> notes.add(agent + ": " + note));
    }

    private Worktree worktree(String id, String name) {
        return manager.create(new WorktreeManager.CreateRequest(new AgentId(id), new AgentId("root"), name, "g",
            "task " + name, root, SpawnMode.BACKGROUND, null, false, null)).orElseThrow();
    }

    private static ToolContext context(String agent) {
        return new ToolContext(Path.of("."), new AgentId(agent), CancellationToken.none(), null, Clock.systemUTC());
    }

    private static tools.jackson.databind.node.ObjectNode input(String... pairs) {
        var node = JSON.createObjectNode();
        for (var i = 0; i < pairs.length; i += 2) node.put(pairs[i], pairs[i + 1]);
        return node;
    }

    @Test
    void definitionsHaveTheNamesAndSchemas() {
        List<Tool> tools = List.of(new SyncDoneTool(manager), new SyncAbortTool(manager),
            new WorktreeMergeTool(commands), new WorktreeDiscardTool(commands));
        assertEquals(List.of("sync_done", "sync_abort", "worktree_merge", "worktree_discard"),
            tools.stream().map(t -> t.definition().name()).toList());
        assertTrue(tools.stream().noneMatch(Tool::readOnly));
        assertTrue(new WorktreeMergeTool(commands).definition().inputSchema().toString().contains("strategy"));
    }

    @Test
    void syncDoneResolvesTheConflictAndSignals() throws Exception {
        var w1 = worktree("a1", "one");
        var w2 = worktree("a2", "two");
        Files.writeString(w1.path.resolve("a.txt"), "one\nW1\nthree\n");
        Files.writeString(w2.path.resolve("a.txt"), "one\nW2\nthree\n");
        for (var w : List.of(w1)) {
            manager.commitRemaining(w);
            assertEquals(WorktreeManager.SyncResult.Kind.CLEAN, manager.sync(w).kind());
            assertEquals(WorktreeManager.MergeResult.Kind.MERGED, manager.mergeBack(w, "merge").kind());
        }
        manager.commitRemaining(w2);
        assertEquals(WorktreeManager.SyncResult.Kind.CONFLICT, manager.sync(w2).kind());
        var done = new SyncDoneTool(manager);
        var markers = done.execute(input("notes", "n"), context("a2"));
        assertTrue(markers.error());
        assertTrue(markers.output().contains("Conflict markers remain"));
        assertTrue(manager.takeSignal(w2.agent).isEmpty());
        Files.writeString(w2.path.resolve("a.txt"), "one\nboth\nthree\n");
        var ok = done.execute(input("notes", "merged both"), context("a2"));
        assertFalse(ok.error(), ok.output());
        var signal = manager.takeSignal(w2.agent).orElseThrow();
        assertEquals(WorktreeManager.Resolution.DONE, signal.resolution());
        assertEquals("merged both", signal.text());
        assertTrue(done.execute(input(), context("nobody")).error(), "an agent without a worktree gets an error");
    }

    @Test
    void syncAbortSignalsAndNeedsAReason() throws Exception {
        var w = worktree("a1", "one");
        var abort = new SyncAbortTool(manager);
        assertTrue(abort.execute(input(), context("a1")).error(), "reason is required");
        assertFalse(abort.execute(input("reason", "too hard"), context("a1")).error());
        var signal = manager.takeSignal(w.agent).orElseThrow();
        assertEquals(WorktreeManager.Resolution.ABORT, signal.resolution());
        assertEquals("too hard", signal.text());
        assertTrue(abort.execute(input("reason", "x"), context("nobody")).error());
    }

    @Test
    void mergeToolRunsTheManualSequenceAndNotesTheCleanup() throws Exception {
        var w = worktree("a1", "feature");
        Files.writeString(w.path.resolve("f.txt"), "f\n");
        var tool = new WorktreeMergeTool(commands);
        var result = tool.execute(input("agent", "feature", "strategy", "squash"), context("root"));
        assertFalse(result.error(), result.output());
        assertTrue(Files.exists(root.resolve("f.txt")));
        assertEquals("jeorje: feature", g(root, "log", "-1", "--format=%s"));
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).startsWith("root: worktree removed: feature"));
        assertTrue(tool.execute(input("agent", "nobody"), context("root")).error());
        assertTrue(commands.list().isEmpty());
        assertEquals("No worktrees.", commands.listText());
    }

    @Test
    void discardToolRemovesTheWorktree() throws Exception {
        var w = worktree("a1", "scrap");
        Files.writeString(w.path.resolve("s.txt"), "s\n");
        assertTrue(commands.listText().contains("scrap | jeorje/g/scrap"));
        assertTrue(commands.listText().contains("changed"));
        var result = new WorktreeDiscardTool(commands).execute(input("agent", "a1"), context("root"));
        assertFalse(result.error(), result.output());
        assertFalse(Files.exists(w.path));
        assertEquals("", g(root, "branch", "--list", "jeorje/*"));
        assertTrue(new WorktreeDiscardTool(commands).execute(input("agent", "a1"), context("root")).error());
    }

    @Test
    void pruneCommandRemovesMergedWorktrees() throws Exception {
        var w = worktree("a1", "done");
        Files.writeString(w.path.resolve("d.txt"), "d\n");
        manager.commitRemaining(w);
        manager.sync(w);
        manager.mergeBack(w, "merge");
        var result = commands.prune();
        assertTrue(result.ok());
        assertTrue(result.message().contains("Removed 1"));
        assertEquals("Nothing to prune.", commands.prune().message());
    }
}
