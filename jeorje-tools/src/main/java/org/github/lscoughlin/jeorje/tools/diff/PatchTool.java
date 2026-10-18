package org.github.lscoughlin.jeorje.tools.diff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.github.lscoughlin.jeorje.core.diff.ApplyOptions;
import org.github.lscoughlin.jeorje.core.diff.FileApplyResult;
import org.github.lscoughlin.jeorje.core.diff.FileDiff;
import org.github.lscoughlin.jeorje.core.diff.SplitText;
import org.github.lscoughlin.jeorje.core.diff.TextDiff;
import org.github.lscoughlin.jeorje.core.diff.TextDiffException;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.state.AgentState;
import org.github.lscoughlin.jeorje.core.state.FileState;
import org.github.lscoughlin.jeorje.core.state.StateUpdate;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import tools.jackson.databind.json.JsonMapper;

/** The {@code patch} tool. It applies a unified diff to files in the workspace, all or nothing. */
public final class PatchTool extends RecordTool<PatchTool.Input> {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The input of {@code patch}. */
    public record Input(
            @Describe("The diff text. Use diff or diffFile.") String diff,
            @Describe("Path of a file that holds the diff.") String diffFile,
            @Describe("Number of leading path parts to remove. Default auto: remove a/ and b/.") String strip,
            @Describe("If true, check the diff and write nothing.") Boolean dryRun,
            @Describe("The highest number of context lines that can differ (0 to 3). Default 2.") Integer fuzz,
            @Describe("If true, undo the diff.") Boolean reverse,
            @Describe("fail (default) or markers.") String conflicts) {}

    /** The file operations of the tool. A test can replace them to cause a write failure. */
    public interface FileOps {
        void write(Path file, String content) throws IOException;

        void delete(Path file) throws IOException;
    }

    /** The file operations that the tool uses by default. */
    public static FileOps defaultOps() {
        return DEFAULT_OPS;
    }

    private static final FileOps DEFAULT_OPS = new FileOps() {
        @Override
        public void write(Path file, String content) throws IOException {
            atomicWrite(file, content);
        }

        @Override
        public void delete(Path file) throws IOException {
            Files.delete(file);
        }
    };

    private final PermissionPolicy policy;
    private final AgentState state;
    private final int defaultFuzz;
    private final String defaultConflicts;
    private final FileOps ops;

    public PatchTool(PermissionPolicy policy, AgentState state) {
        this(policy, state, 2, DEFAULT_OPS);
    }

    public PatchTool(PermissionPolicy policy, AgentState state, int defaultFuzz, FileOps ops) {
        this(policy, state, defaultFuzz, "fail", ops);
    }

    /**
     * @param defaultFuzz the fuzz when the call gives none ({@code tools.patch.fuzz})
     * @param defaultConflicts {@code fail} or {@code markers}, when the call gives none ({@code tools.patch.conflicts})
     */
    public PatchTool(PermissionPolicy policy, AgentState state, int defaultFuzz, String defaultConflicts,
                     FileOps ops) {
        super("patch", "Apply a unified diff to files in the workspace. The diff can name many files. "
                + "Existing files must be read first in this session. If any hunk fails, no file changes.",
                Input.class, false);
        this.policy = policy;
        this.state = state;
        this.defaultFuzz = defaultFuzz;
        this.defaultConflicts = defaultConflicts == null ? "fail" : defaultConflicts;
        this.ops = ops;
    }

    private record Planned(Path file, String shown, byte[] before, SplitText beforeText, FileApplyResult result) {}

    @Override
    protected ToolResultData run(Input input, ToolContext context) throws Exception {
        if ((input.diff() == null) == (input.diffFile() == null)) {
            return ToolResultData.error("Use exactly one of diff and diffFile.");
        }
        var base = context.workingDirectory().toAbsolutePath().normalize();
        String diffText;
        try {
            diffText = input.diff() != null ? input.diff() : readDiffFile(base, input.diffFile());
        } catch (Refused e) {
            return ToolResultData.error(e.getMessage());
        }
        var strip = -1;
        if (input.strip() != null && !input.strip().equals("auto")) {
            try {
                strip = Integer.parseInt(input.strip().trim());
            } catch (NumberFormatException e) {
                return ToolResultData.error("strip must be auto or a number.");
            }
            if (strip < 0) return ToolResultData.error("strip must be auto or a number.");
        }
        var fuzz = input.fuzz() == null ? defaultFuzz : input.fuzz();
        if (fuzz < 0 || fuzz > 3) return ToolResultData.error("fuzz must be 0 to 3.");
        var conflicts = input.conflicts() == null ? defaultConflicts : input.conflicts();
        if (!conflicts.equals("fail") && !conflicts.equals("markers")) {
            return ToolResultData.error("conflicts must be fail or markers.");
        }
        var reverse = Boolean.TRUE.equals(input.reverse());
        var dryRun = Boolean.TRUE.equals(input.dryRun());
        var options = new ApplyOptions(fuzz, reverse,
                conflicts.equals("markers") ? ApplyOptions.Conflicts.MARKERS : ApplyOptions.Conflicts.FAIL);

        List<FileDiff> files;
        try {
            files = TextDiff.parse(diffText);
        } catch (TextDiffException e) {
            return ToolResultData.error("Cannot read the diff: " + e.getMessage());
        }
        if (files.isEmpty()) return ToolResultData.error("The diff has no files.");

        // Check every file first, then apply every file in memory.
        var planned = new ArrayList<Planned>();
        var problems = new ArrayList<String>();
        var seen = new HashSet<Path>();
        for (var file : files) {
            var effective = reverse ? file.reversed() : file;
            var name = effective.path(strip);
            Path path;
            try {
                path = checkPath(name, base);
            } catch (Refused e) {
                problems.add(e.getMessage());
                continue;
            }
            var shown = display(base, path);
            if (!seen.add(path)) {
                problems.add("Refused: the diff names " + shown + " more than once.");
                continue;
            }
            byte[] bytes = null;
            SplitText original = null;
            var exists = Files.exists(path);
            if (effective.kind() == FileDiff.Kind.CREATE) {
                if (exists) {
                    problems.add("Refused: " + shown + " already exists. A diff that creates a file needs no existing file.");
                    continue;
                }
            } else {
                if (!Files.isRegularFile(path)) {
                    problems.add("File does not exist: " + shown);
                    continue;
                }
                try {
                    bytes = Files.readAllBytes(path);
                    var known = state.files().get(path);
                    if (known.isEmpty()) {
                        problems.add("Refused: " + shown + " exists and you did not read it in this session. "
                                + "Read it with the read tool first.");
                        continue;
                    }
                    if (!known.get().hash().equals(hash(bytes))) {
                        problems.add("Refused: " + shown + " changed on disk after you read it. "
                                + "Read it again with the read tool first.");
                        continue;
                    }
                    original = SplitText.decode(bytes);
                } catch (TextDiffException e) {
                    problems.add("Cannot change " + shown + ": " + e.getMessage());
                    continue;
                }
            }
            FileApplyResult result;
            try {
                result = TextDiff.apply(file, original, strip, options);
            } catch (TextDiffException e) {
                problems.add("Cannot apply the diff to " + shown + ": " + e.getMessage());
                continue;
            }
            planned.add(new Planned(path, shown, bytes, original, result));
        }
        for (var plan : planned) {
            if (plan.result().status() == FileApplyResult.Status.FAILED) {
                problems.add(failureReport(plan));
            }
        }
        if (!problems.isEmpty()) {
            return ToolResultData.error("The patch was not applied. No file changed.\n" + String.join("\n", problems));
        }

        var report = new StringBuilder();
        var diffs = new StringBuilder();
        var writes = new ArrayList<Planned>();
        for (var plan : planned) {
            var result = plan.result();
            var status = result.deleted() ? "deleted" : result.created() ? "created" : "modified";
            if (result.status() == FileApplyResult.Status.CONFLICTED) status = "conflicted (" + status + " with markers)";
            report.append(status).append(' ').append(plan.shown()).append('\n');
            for (var hunk : result.hunks()) {
                report.append("  hunk ").append(hunk.index()).append(": offset ").append(hunk.offset())
                        .append(", fuzz ").append(hunk.fuzz()).append('\n');
            }
            for (var failure : result.failures()) {
                report.append("  hunk ").append(failure.index()).append(" conflict: ").append(failure.reason()).append('\n');
            }
            var after = result.result() == null ? SplitText.empty() : result.result();
            var before = plan.beforeText() == null ? SplitText.empty() : plan.beforeText();
            var oldName = plan.beforeText() == null ? "/dev/null" : "a/" + plan.shown();
            var newName = result.deleted() ? "/dev/null" : "b/" + plan.shown();
            diffs.append(TextDiff.unified(oldName, newName, before, after, TextDiff.Options.defaults()).unified());
            writes.add(plan);
        }
        if (dryRun) {
            return ToolResultData.ok("Dry run. Nothing was written.\n" + report + diffs);
        }

        var done = new ArrayList<Planned>();
        var updates = new ArrayList<StateUpdate>();
        try {
            for (var plan : writes) {
                var result = plan.result();
                if (result.result() == null) {
                    ops.delete(plan.file());
                } else {
                    ops.write(plan.file(), result.result().render());
                }
                done.add(plan);
            }
        } catch (IOException | RuntimeException e) {
            var failed = restore(done);
            return ToolResultData.error("The patch failed while it wrote files: " + e.getMessage()
                    + ". " + (failed.isEmpty() ? "All files that were written were restored."
                    : "Could not restore: " + String.join(", ", failed) + "."));
        }
        for (var plan : done) {
            if (plan.result().result() == null) continue;
            var bytes = plan.result().result().render().getBytes(StandardCharsets.UTF_8);
            updates.add(new StateUpdate.FileRecorded(plan.file(),
                    new FileState(Files.getLastModifiedTime(plan.file()).toMillis(), hash(bytes))));
        }
        return ToolResultData.ok("Applied the patch.\n" + report + diffs, updates);
    }

    private List<String> restore(List<Planned> done) {
        var failed = new ArrayList<String>();
        for (var plan : done) {
            try {
                if (plan.before() == null) {
                    Files.deleteIfExists(plan.file());
                } else {
                    atomicWrite(plan.file(), new String(plan.before(), StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                failed.add(plan.shown());
            }
        }
        return failed;
    }

    private static String failureReport(Planned plan) {
        var sb = new StringBuilder();
        var result = plan.result();
        sb.append("Failed: ").append(plan.shown()).append('\n');
        if (result.error() != null) sb.append("  ").append(result.error()).append('\n');
        for (var failure : result.failures()) {
            sb.append("  hunk ").append(failure.index()).append(' ').append(failure.header()).append(": ")
                    .append(failure.reason()).append(" (expected near line ").append(failure.expectedLine())
                    .append(")\n");
            for (var line : failure.nearbyLines()) sb.append("    ").append(line).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static final class Refused extends Exception {
        Refused(String message) { super(message); }
    }

    private String readDiffFile(Path base, String name) throws Refused {
        var path = checkPath(name, base);
        if (!Files.isRegularFile(path)) throw new Refused("File does not exist: " + name);
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new Refused("Cannot read " + name + ": " + e.getMessage());
        }
    }

    /** Resolves a path. It must stay in the workspace, must not be under .git, and the policy must allow it. */
    private Path checkPath(String name, Path base) throws Refused {
        if (name == null || name.isBlank()) throw new Refused("The diff has an empty file name.");
        var resolved = base.resolve(name).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            throw new Refused("Refused: " + name + " is outside the workspace.");
        }
        for (var part : base.relativize(resolved)) {
            if (part.toString().equals(".git")) {
                throw new Refused("Refused: " + name + " is under .git/. Ask the user to allow this change.");
            }
        }
        if (policy != null) {
            var input = JSON.createObjectNode().put("path", resolved.toString());
            if (policy.check(new ToolUse("file-patch", "patch", input)) == PermissionPolicy.Decision.DENY) {
                throw new Refused("Refused: the permission policy does not allow patch on " + name + ".");
            }
        }
        return resolved;
    }

    private static String display(Path base, Path file) {
        return base.relativize(file).toString().replace('\\', '/');
    }

    private static String hash(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void atomicWrite(Path file, String content) throws IOException {
        var dir = file.getParent();
        Files.createDirectories(dir);
        var temp = Files.createTempFile(dir, ".jeorje-", ".tmp");
        try {
            Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
            if (Files.exists(file)) {
                try {
                    Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(file));
                } catch (UnsupportedOperationException | IOException ignored) {
                    // Permissions are best effort.
                }
            }
            try {
                Files.move(temp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
