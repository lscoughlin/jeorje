package org.github.lscoughlin.jeorje.core.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.subagent.SpawnMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes all git calls for the worktrees of subagents. It creates and removes worktrees, syncs them from the source
 * branch, and merges them back. One lock for each repository serializes the calls that change repository metadata.
 */
public final class WorktreeManager {
    private static final Logger log = LoggerFactory.getLogger(WorktreeManager.class);
    private static final Map<String, ReentrantLock> REPO_LOCKS = new ConcurrentHashMap<>();
    private static final Map<String, ReentrantLock> INTEGRATION_LOCKS = new ConcurrentHashMap<>();
    private static final String FALLBACK_NAME = "Jeorje";
    private static final String FALLBACK_EMAIL = "jeorje@localhost";

    private final GitRunner git;
    private final Path projectRoot;
    private final JeorjeConfig.Worktree config;
    private final Consumer<String> notices;
    private final Map<AgentId, Worktree> registry = new LinkedHashMap<>();
    private volatile Consumer<Path> processStopper = path -> {};
    private volatile boolean fallbackNoticeShown;
    private volatile boolean identityWarned;
    private final Map<AgentId, Signal> resolutions = new ConcurrentHashMap<>();

    /**
     * @param git the git runner
     * @param projectRoot the main project directory ({@code ${CWD}})
     * @param config the {@code worktree} config
     * @param notices receives the one-time notices for the user
     */
    public WorktreeManager(GitRunner git, Path projectRoot, JeorjeConfig.Worktree config, Consumer<String> notices) {
        this.git = git;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.config = config;
        this.notices = notices == null ? text -> {} : notices;
    }

    public GitRunner git() { return git; }

    public JeorjeConfig.Worktree config() { return config; }

    public Path projectRoot() { return projectRoot; }

    /** Sets the action that stops the processes that run in a worktree, for example background shells. */
    public void processStopper(Consumer<Path> stopper) { this.processStopper = stopper == null ? p -> {} : stopper; }

    // ---- requests and results ----

    /**
     * What a spawn gives to the manager.
     *
     * @param isolation the per-call field {@code worktree} or {@code shared}, or null
     * @param explicitWorkdir true if the spawn set {@code workdir}
     * @param sourceDir the working directory of the parent agent
     * @param verify the verification command of the story, or null
     */
    public record CreateRequest(AgentId agent, AgentId parent, String name, String group, String task, Path sourceDir,
                                SpawnMode mode, String isolation, boolean explicitWorkdir, String verify) {}

    /** The result of a sync. */
    public record SyncResult(Kind kind, List<String> conflicts, String status, List<String> incoming, String output) {
        /** The kind of result. */
        public enum Kind { CLEAN, CONFLICT, ERROR }
    }

    /** The result of {@code sync_done}. */
    public record DoneResult(Kind kind, List<String> files, String message) {
        /** The kind of result. */
        public enum Kind { DONE, CONFLICT, MARKERS, ERROR }
    }

    /** The result of a merge back. */
    public record MergeResult(Kind kind, List<String> files, String commit, String message) {
        /** The kind of result. */
        public enum Kind { MERGED, DIRTY, ERROR }
    }

    /** The result of a cleanup. */
    public record CleanupResult(boolean removed, boolean branchDeleted, String note, String keepReason) {}

    /** One row of {@code /worktrees}. */
    public record Row(AgentId agent, String name, String branch, Path path, String state) {}

    /** The facts from the startup check. */
    public record StartupReport(List<Path> leftover, List<Path> missing) {}

    /** What the tools {@code sync_done} and {@code sync_abort} decided. */
    public enum Resolution { DONE, ABORT }

    /** A decision with its text: the notes of {@code sync_done} or the reason of {@code sync_abort}. */
    public record Signal(Resolution resolution, String text) {}

    // ---- registry ----

    /** The worktree of an agent. */
    public synchronized Optional<Worktree> byAgent(AgentId agent) { return Optional.ofNullable(registry.get(agent)); }

    /** Finds a worktree by agent id value, agent name, or branch. */
    public synchronized Optional<Worktree> find(String key) {
        if (key == null) return Optional.empty();
        for (var w : registry.values()) {
            if (w.agent.value().equals(key) || w.name.equalsIgnoreCase(key) || w.branch.equals(key)) {
                return Optional.of(w);
            }
        }
        return Optional.empty();
    }

    /** All registered worktrees. */
    public synchronized List<Worktree> all() { return List.copyOf(registry.values()); }

    /** Registers a worktree that was restored from the manifest. */
    public synchronized void restore(Worktree worktree) { registry.put(worktree.agent, worktree); }

    private synchronized void unregister(Worktree w) {
        registry.remove(w.agent);
        resolutions.remove(w.agent);
    }

    // ---- isolation ----

    /**
     * Makes the worktree for a new subagent, or returns empty when the subagent must use the shared directory.
     *
     * @throws WorktreeException if a worktree is required (mode {@code always} or isolation {@code worktree}) and
     *     cannot be made
     */
    public Optional<Worktree> create(CreateRequest r) {
        var perCall = r.isolation();
        var mode = perCall == null ? config.isolation() : perCall.equals("worktree") ? "always" : "never";
        if (r.explicitWorkdir() || mode.equals("never")) return Optional.empty();
        var required = mode.equals("always");
        try {
            return Optional.of(createWorktree(r));
        } catch (GitException | WorktreeException e) {
            if (required) {
                throw e instanceof WorktreeException we ? we : new WorktreeException(e.getMessage(), e);
            }
            log.atWarn().log(() -> "No worktree for " + r.name() + ": " + e.getMessage());
            if (!fallbackNoticeShown) {
                fallbackNoticeShown = true;
                notices.accept("Worktrees are not available (" + e.getMessage() + "). Subagents share the working directory.");
            }
            return Optional.empty();
        }
    }

    private Worktree createWorktree(CreateRequest r) {
        git.checkVersion();
        var source = r.sourceDir().toAbsolutePath().normalize();
        var inside = git.run(source, "rev-parse", "--is-inside-work-tree");
        if (!inside.ok() || !inside.trimmed().equals("true")) {
            throw new WorktreeException("the directory is not inside a git work tree");
        }
        var head = git.run(source, "symbolic-ref", "--short", "-q", "HEAD");
        if (!head.ok() || head.trimmed().isEmpty()) {
            throw new WorktreeException("HEAD is detached, so there is no source branch");
        }
        var sourceBranch = head.trimmed();
        var top = must(source, "rev-parse", "--show-toplevel").trimmed();
        var sourcePath = realOrSelf(Path.of(top));
        var headCommit = must(source, "rev-parse", "HEAD").trimmed();
        var group = slug(r.group() == null ? "session" : r.group());
        var name = slug(r.name());
        var prefix = config.branchPrefix().isBlank() ? "jeorje" : config.branchPrefix();
        excludeWorktreeDir(source);
        String branch;
        Path path;
        var suffix = 1;
        while (true) {
            var tail = suffix == 1 ? name : name + "-" + suffix;
            branch = prefix + "/" + group + "/" + tail;
            path = projectRoot.resolve(".jeorje").resolve("worktrees").resolve(group).resolve(tail);
            var branchTaken = git.run(source, "show-ref", "--verify", "--quiet", "refs/heads/" + branch).ok();
            if (!branchTaken && !Files.exists(path)) break;
            suffix++;
        }
        var snapshot = config.base().equals("snapshot");
        var base = snapshot ? buildSnapshot(source, headCommit) : headCommit;
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            throw new WorktreeException("Cannot create " + path.getParent() + ": " + e.getMessage(), e);
        }
        var added = mutate(source, "worktree", "add", "-b", branch, path.toString(), base);
        if (!added.ok()) throw new WorktreeException("git worktree add failed: " + added.text());
        copyAndLink(sourcePath, path);
        var w = new Worktree(r.agent(), r.parent(), name, group, path, branch, sourceBranch, sourcePath, base,
            snapshot, r.task(), r.mode(), r.verify());
        synchronized (this) { registry.put(r.agent(), w); }
        log.atInfo().log(() -> "Worktree " + w.path + " on " + w.branch + " from " + sourceBranch);
        return w;
    }

    private String buildSnapshot(Path source, String headCommit) {
        Path index = null;
        try {
            index = Files.createTempFile("jeorje-index", ".tmp");
            Files.delete(index);
            var env = new LinkedHashMap<>(identityEnv(source));
            env.put("GIT_INDEX_FILE", index.toString());
            run(source, env, "read-tree", "HEAD");
            run(source, env, "add", "-A", "--", ".", ":(exclude).jeorje/worktrees");
            var tree = run(source, env, "write-tree").trimmed();
            return run(source, env, "commit-tree", tree, "-p", headCommit, "-m", "jeorje: snapshot").trimmed();
        } catch (IOException e) {
            throw new WorktreeException("Cannot make the snapshot: " + e.getMessage(), e);
        } finally {
            if (index != null) {
                try { Files.deleteIfExists(index); } catch (IOException ignored) { }
            }
        }
    }

    private GitResult run(Path dir, Map<String, String> env, String... args) {
        var result = git.runWith(dir, env, args);
        if (!result.ok()) throw new WorktreeException("git " + args[0] + " failed: " + result.text());
        return result;
    }

    private void excludeWorktreeDir(Path source) {
        try {
            var common = git.run(source, "rev-parse", "--git-common-dir");
            if (!common.ok()) return;
            var dir = source.resolve(common.trimmed()).normalize();
            var exclude = dir.resolve("info").resolve("exclude");
            var line = "/.jeorje/worktrees/";
            var existing = Files.exists(exclude) ? Files.readString(exclude) : "";
            if (existing.lines().anyMatch(l -> l.strip().equals(line))) return;
            Files.createDirectories(exclude.getParent());
            Files.writeString(exclude, (existing.isEmpty() || existing.endsWith("\n") ? "" : "\n") + line + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            log.atDebug().log(() -> "Cannot update info/exclude: " + e.getMessage());
        }
    }

    private void copyAndLink(Path sourcePath, Path worktree) {
        for (var item : config.copy()) {
            var rel = safeRelative(item);
            if (rel == null) continue;
            var from = sourcePath.resolve(rel);
            if (!Files.exists(from)) continue;
            try {
                copyTree(from, worktree.resolve(rel));
            } catch (IOException e) {
                log.atWarn().log(() -> "Cannot copy " + item + ": " + e.getMessage());
            }
        }
        for (var item : config.link()) {
            var rel = safeRelative(item);
            if (rel == null) continue;
            var from = sourcePath.resolve(rel);
            var to = worktree.resolve(rel);
            if (!Files.exists(from) || Files.exists(to, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                Files.createDirectories(to.getParent());
                Files.createSymbolicLink(to, from);
            } catch (IOException | UnsupportedOperationException e) {
                log.atWarn().log(() -> "Cannot link " + item + ": " + e.getMessage());
            }
        }
    }

    private static Path safeRelative(String item) {
        var p = Path.of(item).normalize();
        if (p.isAbsolute() || p.startsWith("..") || p.toString().isEmpty() || p.startsWith(".git")) {
            log.atWarn().log(() -> "Ignored the unsafe worktree path " + item);
            return null;
        }
        return p;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (Files.isDirectory(from)) {
            try (var walk = Files.walk(from)) {
                for (var p : (Iterable<Path>) walk::iterator) {
                    var target = to.resolve(from.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(target);
                    else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
        } else {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String slug(String text) {
        var s = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^[-.]+|[-.]+$", "");
        s = s.replace("..", ".").replaceAll("\\.lock$", "");
        return s.isEmpty() ? "agent" : s.length() > 60 ? s.substring(0, 60) : s;
    }

    // ---- startup and listing ----

    /**
     * Prunes stale worktree records and compares the worktrees on disk with the manifest.
     *
     * @param manifest the worktree paths in the manifest
     */
    public StartupReport startup(java.util.Collection<Path> manifest) {
        mutate(projectRoot, "worktree", "prune");
        var onDisk = listOnDisk();
        var known = manifest.stream().map(p -> p.toAbsolutePath().normalize()).toList();
        var leftover = onDisk.stream().filter(p -> !known.contains(p)).toList();
        var missing = known.stream().filter(p -> !onDisk.contains(p)).toList();
        return new StartupReport(leftover, missing);
    }

    /** The worktrees that git lists under {@code .jeorje/worktrees}. */
    public List<Path> listOnDisk() {
        var result = git.run(projectRoot, "worktree", "list", "--porcelain");
        var out = new ArrayList<Path>();
        if (!result.ok()) return out;
        var base = projectRoot.resolve(".jeorje").resolve("worktrees");
        for (var line : result.out().split("\n")) {
            if (!line.startsWith("worktree ")) continue;
            var p = realOrSelf(Path.of(line.substring(9)));
            if (p.startsWith(realOrSelf(base))) out.add(p);
        }
        return out;
    }

    private static Path realOrSelf(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    /** The data for {@code /worktrees}. */
    public List<Row> rows() {
        var rows = new ArrayList<Row>();
        for (var w : all()) rows.add(new Row(w.agent, w.name, w.branch, w.path, state(w)));
        return rows;
    }

    /** The state of a worktree: {@code clean}, {@code changed}, or {@code merged}. */
    public String state(Worktree w) {
        if (w.merged()) return "merged";
        if (!Files.isDirectory(w.path)) return "missing";
        return isChanged(w) ? "changed" : "clean";
    }

    /** True if the worktree has uncommitted changes or commits that are not on the source branch. */
    public boolean isChanged(Worktree w) {
        return hasUncommitted(w) || aheadOfSource(w) > 0;
    }

    /** True if the worktree has uncommitted changes or untracked files. */
    public boolean hasUncommitted(Worktree w) {
        var status = git.run(w.path, "status", "--porcelain");
        return !status.ok() || !status.trimmed().isEmpty();
    }

    /** The number of commits of the branch that the source branch does not have (the snapshot commit is not counted). */
    public int aheadOfSource(Worktree w) {
        var args = new ArrayList<>(List.of("rev-list", "--count", w.sourceBranch + ".." + w.branch));
        if (w.snapshot && !w.snapshotDropped()) args.add("^" + w.baseCommit);
        var result = git.run(w.sourcePath, args.toArray(String[]::new));
        if (!result.ok()) return 1;
        try {
            return Integer.parseInt(result.trimmed());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    // ---- commit ----

    /** Commits the remaining changes of a worktree with {@code jeorje: <name>} and the first line of the task. */
    public Optional<String> commitRemaining(Worktree w) {
        var status = must(w.path, "status", "--porcelain");
        if (status.trimmed().isEmpty()) return Optional.empty();
        must(w.path, "add", "-A");
        var message = "jeorje: " + w.name + (w.taskLine().isEmpty() ? "" : "\n\n" + w.taskLine());
        var env = identityEnv(w.path);
        var result = git.runWith(w.path, env, "-c", "commit.gpgsign=false", "commit", "-m", message);
        if (!result.ok()) throw new WorktreeException("git commit failed: " + result.text());
        return Optional.of(head(w.path));
    }

    /** The author and committer variables, or the fallback identity when the git identity is not set. */
    Map<String, String> identityEnv(Path dir) {
        var name = git.run(dir, "config", "user.name");
        var email = git.run(dir, "config", "user.email");
        if (name.ok() && !name.trimmed().isEmpty() && email.ok() && !email.trimmed().isEmpty()) return Map.of();
        if (!identityWarned) {
            identityWarned = true;
            log.atWarn().log(() -> "The git identity is not set. Jeorje commits as " + FALLBACK_NAME + " <" + FALLBACK_EMAIL + ">.");
        }
        var env = new LinkedHashMap<String, String>();
        var n = name.ok() && !name.trimmed().isEmpty() ? name.trimmed() : FALLBACK_NAME;
        var e = email.ok() && !email.trimmed().isEmpty() ? email.trimmed() : FALLBACK_EMAIL;
        env.put("GIT_AUTHOR_NAME", n);
        env.put("GIT_AUTHOR_EMAIL", e);
        env.put("GIT_COMMITTER_NAME", n);
        env.put("GIT_COMMITTER_EMAIL", e);
        return env;
    }

    // ---- sync ----

    /** True if the sync uses a rebase: the strategy {@code rebase}, or a snapshot base. */
    public boolean usesRebase(Worktree w) {
        return config.merge().equals("rebase") || (w.snapshot && !w.snapshotDropped());
    }

    /** Brings the source branch into the branch of the worktree. */
    public SyncResult sync(Worktree w) {
        var incoming = lines(git.run(w.path, "log", "--format=%s", "HEAD.." + w.sourceBranch).out());
        var env = identityEnv(w.path);
        GitResult result;
        if (config.merge().equals("rebase") || w.snapshot) {
            result = w.snapshot && !w.snapshotDropped()
                ? mutateWith(w.path, env, "rebase", "--onto", w.sourceBranch, w.baseCommit)
                : mutateWith(w.path, env, "rebase", w.sourceBranch);
        } else {
            result = mutateWith(w.path, env, "-c", "commit.gpgsign=false", "merge", "--no-edit", w.sourceBranch);
        }
        var progress = inProgress(w);
        if (result.ok() && progress == Progress.NONE) {
            if (w.snapshot) w.snapshotDropped(true);
            return new SyncResult(SyncResult.Kind.CLEAN, List.of(), "", incoming, result.text());
        }
        var conflicts = unmerged(w);
        if (progress != Progress.NONE || !conflicts.isEmpty()) {
            var status = git.run(w.path, "status").text();
            return new SyncResult(SyncResult.Kind.CONFLICT, conflicts, status, incoming, result.text());
        }
        return new SyncResult(SyncResult.Kind.ERROR, List.of(), "", incoming, result.text());
    }

    /** What git operation is in progress in a worktree. */
    enum Progress { NONE, MERGE, REBASE }

    Progress inProgress(Worktree w) {
        if (git.run(w.path, "rev-parse", "-q", "--verify", "MERGE_HEAD").ok()) return Progress.MERGE;
        for (var dir : List.of("rebase-merge", "rebase-apply")) {
            var p = git.run(w.path, "rev-parse", "--git-path", dir);
            if (p.ok() && Files.isDirectory(w.path.resolve(p.trimmed()))) return Progress.REBASE;
        }
        return Progress.NONE;
    }

    /** The files with unresolved conflicts. */
    public List<String> unmerged(Worktree w) {
        return lines(git.run(w.path, "diff", "--name-only", "--diff-filter=U").out());
    }

    /**
     * Ends the resolution: checks the conflict markers, stages all files, and commits the merge or continues the
     * rebase.
     */
    public DoneResult syncDone(Worktree w) {
        must(w.path, "add", "-A");
        var check = git.run(w.path, "diff", "--cached", "--check");
        var markers = lines(check.out() + "\n" + check.err()).stream()
            .filter(l -> l.contains("conflict marker")).toList();
        if (!markers.isEmpty()) {
            return new DoneResult(DoneResult.Kind.MARKERS, markers,
                "Conflict markers remain:\n" + String.join("\n", markers));
        }
        var env = identityEnv(w.path);
        var progress = inProgress(w);
        switch (progress) {
            case MERGE -> {
                var r = mutateWith(w.path, env, "-c", "commit.gpgsign=false", "commit", "--no-edit");
                if (!r.ok()) return new DoneResult(DoneResult.Kind.ERROR, List.of(), "git commit failed: " + r.text());
            }
            case REBASE -> {
                var r = mutateWith(w.path, env, "-c", "commit.gpgsign=false", "rebase", "--continue");
                if (!r.ok() && unmerged(w).isEmpty() && inProgress(w) == Progress.REBASE) {
                    r = mutateWith(w.path, env, "rebase", "--skip");
                }
                var left = unmerged(w);
                if (!left.isEmpty() || inProgress(w) == Progress.REBASE) {
                    return new DoneResult(DoneResult.Kind.CONFLICT, left,
                        "The next commit also has conflicts. Resolve " + String.join(", ", left)
                            + " and call sync_done again.");
                }
                if (!r.ok()) return new DoneResult(DoneResult.Kind.ERROR, List.of(), "git rebase failed: " + r.text());
                if (w.snapshot) w.snapshotDropped(true);
            }
            case NONE -> {
                if (!git.run(w.path, "diff", "--cached", "--quiet").ok()) {
                    var r = mutateWith(w.path, env, "-c", "commit.gpgsign=false", "commit", "-m",
                        "jeorje: " + w.name + " fixes after sync");
                    if (!r.ok()) return new DoneResult(DoneResult.Kind.ERROR, List.of(), "git commit failed: " + r.text());
                }
            }
        }
        if (progress == Progress.MERGE && w.snapshot) w.snapshotDropped(true);
        return new DoneResult(DoneResult.Kind.DONE, List.of(), "The sync is committed.");
    }

    /** Gives up the sync: runs {@code git merge --abort} or {@code git rebase --abort}. */
    public void syncAbort(Worktree w) {
        switch (inProgress(w)) {
            case MERGE -> mutate(w.path, "merge", "--abort");
            case REBASE -> mutate(w.path, "rebase", "--abort");
            case NONE -> { }
        }
    }

    /** Records what the tools {@code sync_done} and {@code sync_abort} decided. */
    public void signal(AgentId agent, Resolution resolution, String text) {
        resolutions.put(agent, new Signal(resolution, text == null ? "" : text));
    }

    /** Takes and clears the recorded resolution of an agent. */
    public Optional<Signal> takeSignal(AgentId agent) { return Optional.ofNullable(resolutions.remove(agent)); }

    /** True if the source tip is an ancestor of the branch. */
    public boolean sourceIsAncestor(Worktree w) {
        return git.run(w.path, "merge-base", "--is-ancestor", w.sourceBranch, w.branch).ok();
    }

    // ---- merge back ----

    /** The strategy that the config sets. */
    public String strategy() { return config.merge(); }

    /**
     * Merges the branch of the worktree into the source branch, in the directory of the source. The caller holds the
     * integration lock.
     */
    public MergeResult mergeBack(Worktree w, String strategy) {
        var current = git.run(w.sourcePath, "symbolic-ref", "--short", "-q", "HEAD");
        if (!current.ok() || !current.trimmed().equals(w.sourceBranch)) {
            return new MergeResult(MergeResult.Kind.ERROR, List.of(), null,
                "The source branch " + w.sourceBranch + " is not checked out in " + w.sourcePath + ".");
        }
        var env = identityEnv(w.sourcePath);
        var message = "jeorje: merge " + w.name + " (" + w.branch + ")";
        GitResult result;
        switch (strategy) {
            case "squash" -> {
                result = mutateWith(w.sourcePath, env, "merge", "--squash", w.branch);
                if (result.ok()) {
                    if (git.run(w.sourcePath, "diff", "--cached", "--quiet").ok()) {
                        w.markMerged(head(w.sourcePath), true);
                        return new MergeResult(MergeResult.Kind.MERGED, List.of(), head(w.sourcePath),
                            "Nothing to squash. The source branch already has the changes.");
                    }
                    var commitMessage = "jeorje: " + w.name + (w.taskLine().isEmpty() ? "" : "\n\n" + w.taskLine());
                    result = mutateWith(w.sourcePath, env, "-c", "commit.gpgsign=false", "commit", "-m", commitMessage);
                }
            }
            case "rebase" -> result = mutateWith(w.sourcePath, env, "merge", "--ff-only", w.branch);
            default -> result = mutateWith(w.sourcePath, env, "-c", "commit.gpgsign=false", "merge", "--no-ff",
                "-m", message, w.branch);
        }
        if (result.ok()) {
            var commit = head(w.sourcePath);
            w.markMerged(commit, strategy.equals("squash"));
            return new MergeResult(MergeResult.Kind.MERGED, List.of(), commit, "Merged " + w.branch + " into "
                + w.sourceBranch + " at " + shortId(commit) + ".");
        }
        var text = result.text();
        if (text.contains("would be overwritten") || text.contains("local changes")) {
            return new MergeResult(MergeResult.Kind.DIRTY, dirtyFiles(text), null, text);
        }
        // A real failure. Restore the parent tree.
        if (strategy.equals("squash")) mutate(w.sourcePath, "reset", "--merge");
        else if (git.run(w.sourcePath, "rev-parse", "-q", "--verify", "MERGE_HEAD").ok()) {
            mutate(w.sourcePath, "merge", "--abort");
        }
        return new MergeResult(MergeResult.Kind.ERROR, List.of(), null, text);
    }

    static List<String> dirtyFiles(String text) {
        var files = new ArrayList<String>();
        for (var line : text.split("\n")) {
            if (line.startsWith("\t") || line.startsWith("    ")) {
                var f = line.strip();
                if (!f.isEmpty() && !files.contains(f)) files.add(f);
            }
        }
        return files;
    }

    // ---- integration lock ----

    /** A held integration lock. Close it to release the lock. */
    public interface IntegrationLock extends AutoCloseable {
        @Override void close();
    }

    /** Takes the integration lock of the source branch. It waits until the lock is free or the token is cancelled. */
    public IntegrationLock lockIntegration(Worktree w, CancellationToken cancel) throws InterruptedException {
        var key = repoKey(w.sourcePath) + "\0" + w.sourceBranch;
        var lock = INTEGRATION_LOCKS.computeIfAbsent(key, k -> new ReentrantLock(true));
        if (cancel != null && cancel.isCancelled()) throw new InterruptedException("cancelled");
        while (!lock.tryLock(100, TimeUnit.MILLISECONDS)) {
            if (cancel != null && cancel.isCancelled()) throw new InterruptedException("cancelled");
        }
        return lock::unlock;
    }

    // ---- cleanup ----

    /**
     * The cleanup by the parent. It stops the processes, removes the worktree, and deletes the branch.
     *
     * @param completed true if the outcome of the subagent is {@code completed}
     */
    public CleanupResult cleanup(Worktree w, boolean completed) {
        var policy = config.onFinish();
        if (policy.equals("keep")) return keep("worktree.onFinish is keep");
        var issues = new ArrayList<String>();
        if (!completed) issues.add("the outcome is not completed");
        if (!w.merged()) issues.add("the merge back did not happen");
        if (Files.isDirectory(w.path)) {
            if (hasUncommitted(w)) issues.add("the worktree has uncommitted changes");
            if (aheadOfSource(w) > 0 && !w.merged()) issues.add("the branch has commits that the source does not have");
        }
        if (!issues.isEmpty() && !policy.equals("remove")) return keep(String.join("; ", issues));
        processStopper.accept(w.path);
        var removed = Files.isDirectory(w.path)
            ? mutate(projectRoot, "worktree", "remove", w.path.toString()) : new GitResult(0, "", "");
        if (!removed.ok() && policy.equals("remove")) {
            removed = mutate(projectRoot, "worktree", "remove", "--force", w.path.toString());
        }
        if (!removed.ok()) return keep("git worktree remove refused: " + removed.text());
        if (!issues.isEmpty()) {
            unregister(w);
            return new CleanupResult(true, false, note(w, false), "kept the branch: " + String.join("; ", issues));
        }
        var deleted = deleteBranch(w);
        unregister(w);
        return new CleanupResult(true, deleted, note(w, deleted), deleted ? null : "the branch was not deleted");
    }

    private CleanupResult keep(String reason) {
        return new CleanupResult(false, false, null, reason);
    }

    private boolean deleteBranch(Worktree w) {
        var dir = Files.isDirectory(w.sourcePath) ? w.sourcePath : projectRoot;
        if (w.squashed()) {
            var commit = w.mergeCommit();
            var onSource = commit != null
                && git.run(dir, "merge-base", "--is-ancestor", commit, w.sourceBranch).ok();
            if (!onSource) return false;
            return mutate(dir, "branch", "-D", w.branch).ok();
        }
        return mutate(dir, "branch", "-d", w.branch).ok();
    }

    private static String note(Worktree w, boolean branchDeleted) {
        return "worktree removed: " + w.name + ", branch " + w.branch
            + (branchDeleted ? " (deleted)" : " (kept)")
            + (w.mergeCommit() != null ? ", merge " + shortId(w.mergeCommit()) : "");
    }

    /** Removes the worktree and deletes the branch with all its commits. */
    public String discard(Worktree w) {
        processStopper.accept(w.path);
        if (Files.isDirectory(w.path)) {
            var r = mutate(projectRoot, "worktree", "remove", "--force", w.path.toString());
            if (!r.ok()) throw new WorktreeException("git worktree remove failed: " + r.text());
        }
        mutate(projectRoot, "worktree", "prune");
        var dir = Files.isDirectory(w.sourcePath) ? w.sourcePath : projectRoot;
        var b = mutate(dir, "branch", "-D", w.branch);
        unregister(w);
        return "Discarded " + w.name + ": removed " + w.path + (b.ok() ? " and deleted the branch " + w.branch
            : ". The branch " + w.branch + " was not deleted: " + b.text());
    }

    /**
     * Removes the worktrees of merged branches that are still on disk, and the leftover directories of discarded
     * worktrees.
     *
     * @return the removed paths
     */
    public List<Path> prune() {
        mutate(projectRoot, "worktree", "prune");
        var removed = new ArrayList<Path>();
        var known = all().stream().collect(java.util.stream.Collectors.toMap(w -> realOrSelf(w.path), w -> w, (a, b) -> a));
        for (var path : listOnDisk()) {
            var w = known.get(path);
            var branch = git.run(path, "symbolic-ref", "--short", "-q", "HEAD").trimmed();
            var target = w != null ? w.sourceBranch : currentBranch(projectRoot);
            var dir = w != null && Files.isDirectory(w.sourcePath) ? w.sourcePath : projectRoot;
            if (branch.isEmpty() || target.isEmpty()) continue;
            var merged = w != null ? w.merged()
                : git.run(dir, "merge-base", "--is-ancestor", branch, target).ok();
            if (!merged || !git.run(path, "status", "--porcelain").trimmed().isEmpty()) continue;
            processStopper.accept(path);
            if (mutate(projectRoot, "worktree", "remove", path.toString()).ok()) {
                if (w != null && w.squashed()) mutate(dir, "branch", "-D", branch);
                else mutate(dir, "branch", "-d", branch);
                if (w != null) unregister(w);
                removed.add(path);
            }
        }
        var base = projectRoot.resolve(".jeorje").resolve("worktrees");
        if (Files.isDirectory(base)) {
            try (var walk = Files.walk(base, 2)) {
                var onDisk = listOnDisk();
                for (var dir : (Iterable<Path>) walk.filter(Files::isDirectory)::iterator) {
                    if (Files.isRegularFile(dir.resolve(".git")) && !onDisk.contains(realOrSelf(dir))
                        && all().stream().noneMatch(x -> realOrSelf(x.path).equals(realOrSelf(dir)))) {
                        deleteTree(dir);
                        removed.add(dir);
                    }
                }
            } catch (IOException e) {
                log.atWarn().log(() -> "Cannot scan the worktree directory: " + e.getMessage());
            }
        }
        return removed;
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (var p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
        }
    }

    // ---- helpers ----

    private String currentBranch(Path dir) {
        return git.run(dir, "symbolic-ref", "--short", "-q", "HEAD").trimmed();
    }

    String head(Path dir) { return git.run(dir, "rev-parse", "HEAD").trimmed(); }

    static String shortId(String commit) { return commit == null ? "" : commit.length() > 8 ? commit.substring(0, 8) : commit; }

    private static List<String> lines(String text) {
        return text.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private GitResult must(Path dir, String... args) {
        var result = git.run(dir, args);
        if (!result.ok()) throw new WorktreeException("git " + args[0] + " failed: " + result.text());
        return result;
    }

    private String repoKey(Path dir) {
        var common = git.run(dir, "rev-parse", "--git-common-dir");
        var text = common.ok() ? common.trimmed() : ".git";
        return realOrSelf(dir.resolve(text)).toString();
    }

    /** Runs a git call that changes repository metadata, under the lock of the repository. */
    private GitResult mutate(Path dir, String... args) { return mutateWith(dir, Map.of(), args); }

    private GitResult mutateWith(Path dir, Map<String, String> env, String... args) {
        var lock = REPO_LOCKS.computeIfAbsent(repoKey(dir), k -> new ReentrantLock());
        lock.lock();
        try {
            return git.runWith(dir, env, args);
        } finally {
            lock.unlock();
        }
    }
}
