package org.github.lscoughlin.jeorje.core.subagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.worktree.GitException;
import org.github.lscoughlin.jeorje.core.worktree.GitRunner;

/**
 * Decides if a finished tool call of a subagent is real progress. A successful {@code edit}, {@code write}, or
 * {@code patch} is progress when it brings the workspace to a state that the subagent did not have before. The
 * state is a fingerprint of the diff of the workspace. A change that goes back to an earlier state (edit and undo)
 * is not progress. The signal fires at most once in each {@value #MIN_CALLS_BETWEEN_RESETS} model calls.
 *
 * <p>Entry points that the code reuses (no second path): {@link SubagentRuntime#noteProgress} gives the budget
 * back, and the runtime calls {@link #toolStarted}, {@link #toolFinished}, {@link #modelCall}, and
 * {@link #budgetReset} from its one event handler. One agent thread uses an instance.
 */
public final class ProgressSignals {
    /** The least number of model calls between two signals. */
    public static final int MIN_CALLS_BETWEEN_RESETS = 10;
    /** The tools that change files. */
    static final Set<String> CHANGING_TOOLS = Set.of("edit", "write", "patch");
    private static final int MAX_STATES = 2_000;
    private static final int MAX_UNTRACKED_FILES = 200;
    private static final long MAX_UNTRACKED_BYTES = 2_000_000;

    /** Reads the state of a workspace. */
    @FunctionalInterface
    public interface Fingerprint {
        /** The state of the workspace as a short text, or empty when the workspace has no readable state. */
        Optional<String> of(Path workspace);
    }

    private final Path workspace;
    private final Fingerprint fingerprint;
    private final Map<String, String> running = new HashMap<>();
    private final Set<String> seen = new HashSet<>();
    private int callsSinceReset = MIN_CALLS_BETWEEN_RESETS;
    private int counter;

    public ProgressSignals(Path workspace, Fingerprint fingerprint) {
        this.workspace = workspace;
        this.fingerprint = fingerprint;
    }

    /** A tool call starts. The first call of a file-changing tool records the state before the change. */
    public void toolStarted(String toolUseId, String tool) {
        if (!CHANGING_TOOLS.contains(tool)) return;
        running.put(toolUseId, tool);
        if (seen.isEmpty()) fingerprint.of(workspace).ifPresent(seen::add);
    }

    /** One model call happened. */
    public void modelCall() {
        if (callsSinceReset < Integer.MAX_VALUE) callsSinceReset++;
    }

    /** The subagent got a new budget of model calls from any source. The 10 calls count from here. */
    public void budgetReset() { callsSinceReset = 0; }

    /**
     * A tool call ends.
     *
     * @return true when the call is progress and the subagent gets a new budget
     */
    public boolean toolFinished(String toolUseId, ToolResultData result) {
        var tool = running.remove(toolUseId);
        if (tool == null || result == null || result.error()) return false;
        var state = fingerprint.of(workspace);
        // A workspace without a readable state: each change is new, and the rate limit still applies.
        var key = state.orElseGet(() -> "change-" + (++counter));
        if (seen.size() >= MAX_STATES) seen.clear();
        var fresh = seen.add(key);
        if (!fresh || callsSinceReset < MIN_CALLS_BETWEEN_RESETS) return false;
        callsSinceReset = 0;
        return true;
    }

    /** The fingerprint of a git work tree: HEAD, the diff against HEAD, and the content of untracked files. */
    public static Fingerprint git(GitRunner git) {
        return dir -> {
            try {
                var inside = git.run(dir, "rev-parse", "--is-inside-work-tree");
                if (!inside.ok() || !inside.trimmed().equals("true")) return Optional.empty();
                var sha = MessageDigest.getInstance("SHA-256");
                var head = git.run(dir, "rev-parse", "-q", "--verify", "HEAD");
                update(sha, head.ok() ? head.trimmed() : "no-head");
                var diff = git.run(dir, "diff", "HEAD", "--no-color", "--no-ext-diff");
                update(sha, diff.out());
                var others = git.run(dir, "ls-files", "--others", "--exclude-standard");
                if (others.ok()) {
                    var count = 0;
                    for (var name : others.out().split("\n")) {
                        if (name.isBlank()) continue;
                        update(sha, name);
                        if (++count > MAX_UNTRACKED_FILES) break;
                        var file = dir.resolve(name);
                        try {
                            if (Files.isRegularFile(file) && Files.size(file) <= MAX_UNTRACKED_BYTES) {
                                sha.update(Files.readAllBytes(file));
                            }
                        } catch (IOException e) {
                            update(sha, "unreadable");
                        }
                    }
                }
                return Optional.of(HexFormat.of().formatHex(sha.digest()));
            } catch (GitException | NoSuchAlgorithmException e) {
                return Optional.empty();
            }
        };
    }

    private static void update(MessageDigest sha, String text) {
        sha.update(text.getBytes(StandardCharsets.UTF_8));
        sha.update((byte) 0);
    }
}
