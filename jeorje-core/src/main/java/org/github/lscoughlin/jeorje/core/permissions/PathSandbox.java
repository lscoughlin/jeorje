package org.github.lscoughlin.jeorje.core.permissions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The path sandbox. It resolves each path with {@code toRealPath}, so symbolic links and
 * {@code ..} segments are gone before the check.
 */
public final class PathSandbox {

    /** Where a resolved path is. */
    public enum Area { WORKSPACE, READ_ONLY, OUTSIDE }

    /** The result of a path check. */
    public record Result(Path resolved, Area area, boolean sensitive) {}

    private final Path workspace;
    private final List<Path> writable = new ArrayList<>();
    private final List<Path> readOnly = new ArrayList<>();
    private final List<Path> sensitiveDirs = new ArrayList<>();

    public PathSandbox(Path workspace, List<Path> extraDirectories, List<Path> readOnlyDirectories,
                       List<Path> configDirectories) {
        this.workspace = realOf(workspace.toAbsolutePath());
        writable.add(this.workspace);
        extraDirectories.forEach(d -> writable.add(realOf(d.toAbsolutePath())));
        readOnlyDirectories.forEach(d -> readOnly.add(realOf(d.toAbsolutePath())));
        sensitiveDirs.add(this.workspace.resolve(".jeorje"));
        configDirectories.forEach(d -> sensitiveDirs.add(realOf(d.toAbsolutePath())));
    }

    public Path workspace() {
        return workspace;
    }

    /** The directories that a write tool may change, besides the workspace. */
    public List<Path> extraDirectories() {
        return List.copyOf(writable.subList(1, writable.size()));
    }

    /** The read-only directories. */
    public List<Path> readOnlyDirectories() {
        return List.copyOf(readOnly);
    }

    /**
     * A sandbox with another workspace and the same extra directories, read-only directories, and sensitive
     * directories. The sensitive directory {@code .jeorje} of the old workspace is not carried over. The new
     * workspace gets its own.
     */
    public PathSandbox withWorkspace(Path newWorkspace) {
        var configDirs = new ArrayList<Path>();
        for (var dir : sensitiveDirs) {
            if (!dir.equals(workspace.resolve(".jeorje"))) configDirs.add(dir);
        }
        return new PathSandbox(newWorkspace, extraDirectories(), readOnlyDirectories(), configDirs);
    }

    /** Resolves a path. A relative path is relative to the workspace. */
    public Result check(String path) {
        var raw = Path.of(path);
        var base = raw.isAbsolute() ? raw : workspace.resolve(raw);
        var real = realOf(base);
        var sensitive = false;
        var area = Area.OUTSIDE;
        for (var root : writable) {
            if (real.startsWith(root)) {
                area = Area.WORKSPACE;
                sensitive |= isSensitive(root, real);
            }
        }
        if (area == Area.OUTSIDE) {
            for (var root : readOnly) {
                if (real.startsWith(root)) area = Area.READ_ONLY;
            }
        }
        for (var dir : sensitiveDirs) {
            if (real.startsWith(dir)) sensitive = true;
        }
        return new Result(real, area, sensitive);
    }

    private static boolean isSensitive(Path root, Path real) {
        for (var segment : root.relativize(real)) {
            if (segment.toString().equals(".git")) return true;
        }
        return false;
    }

    /**
     * Resolves symbolic links and {@code ..}. If the file does not exist, Jeorje resolves the
     * nearest existing parent and appends the rest.
     */
    static Path realOf(Path path) {
        return realOf(path, 0);
    }

    private static Path realOf(Path path, int depth) {
        var normalizedInput = path.toAbsolutePath();
        var existing = normalizedInput;
        var rest = new ArrayList<Path>();
        while (existing != null && !Files.exists(existing)) {
            if (Files.isSymbolicLink(existing) && depth < 16) {
                try {
                    var target = existing.getParent().resolve(Files.readSymbolicLink(existing));
                    var real = realOf(target, depth + 1);
                    for (var part : rest) real = real.resolve(part);
                    return real.normalize();
                } catch (IOException e) {
                    return normalizedInput.normalize();
                }
            }
            var name = existing.getFileName();
            if (name != null) rest.add(0, name);
            existing = existing.getParent();
        }
        if (existing == null) return normalizedInput.normalize();
        try {
            var real = existing.toRealPath();
            for (var part : rest) real = real.resolve(part);
            return real.normalize();
        } catch (IOException e) {
            return normalizedInput.normalize();
        }
    }
}
