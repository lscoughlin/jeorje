package org.github.lscoughlin.jeorje.core.instructions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds the ordered list of directories to scan for skills, commands, and agent definitions. */
public final class Scopes {

    /** The scope of a root. A lower ordinal has higher precedence. */
    public enum Scope { PROJECT_JEORJE, PROJECT_AGENTS, USER_JEORJE, USER_AGENTS, EXTRA, COMPAT }

    /** A directory to scan. */
    public record Root(Scope scope, Path dir, boolean project) {
    }

    private Scopes() {
    }

    /**
     * @param kind         "skills", "commands", or "agents"
     * @param projectDir   the project directory (for a worktree: the main project directory)
     * @param projectTrusted whether project roots are included
     * @param extraBases   directories from skills.dirs and agents.dirs; each has skills/, commands/, agents/
     * @param compatDirs   extra skill directories used as they are (only for kind "skills")
     */
    public static List<Root> roots(String kind, Path home, Path projectDir, boolean projectTrusted,
                                   List<Path> extraBases, List<Path> compatDirs) {
        var roots = new ArrayList<Root>();
        if (projectTrusted && projectDir != null) {
            roots.add(new Root(Scope.PROJECT_JEORJE, projectDir.resolve(".jeorje").resolve(kind), true));
            roots.add(new Root(Scope.PROJECT_AGENTS, projectDir.resolve(".agents").resolve(kind), true));
        }
        if (home != null) {
            roots.add(new Root(Scope.USER_JEORJE, home.resolve(".jeorje").resolve(kind), false));
            roots.add(new Root(Scope.USER_AGENTS, home.resolve(".agents").resolve(kind), false));
        }
        for (var base : extraBases) {
            roots.add(new Root(Scope.EXTRA, base.resolve(kind), false));
        }
        if (kind.equals("skills")) {
            for (var dir : compatDirs) {
                roots.add(new Root(Scope.COMPAT, dir, false));
            }
        }
        return roots;
    }

    /** True if the project has any gated item directory with content. */
    public static boolean hasProjectItems(Path projectDir) {
        for (var base : List.of(".jeorje", ".agents")) {
            for (var kind : List.of("skills", "commands", "agents")) {
                var dir = projectDir.resolve(base).resolve(kind);
                if (java.nio.file.Files.isDirectory(dir)) {
                    try (var s = java.nio.file.Files.list(dir)) {
                        if (s.findAny().isPresent()) {
                            return true;
                        }
                    } catch (java.io.IOException e) {
                        // Treat as no items.
                    }
                }
            }
        }
        return false;
    }
}
