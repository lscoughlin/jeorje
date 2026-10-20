package org.github.lscoughlin.jeorje.tools.search;

import java.util.List;
import java.util.Set;

/**
 * Which names the search tools skip. The tools always skip {@code .git} and {@code .jeorje}. A call with
 * {@code includeIgnored} searches the other names in these lists too.
 *
 * @param skipDirs names of directories that the tools skip at any depth
 * @param skipFiles file names or {@code *.suffix} patterns that the tools skip
 */
public record SearchOptions(Set<String> skipDirs, List<String> skipFiles) {
    /** The default directory names. */
    public static final List<String> DEFAULT_DIRS = List.of("target", "build", "out", "node_modules", ".gradle",
            ".idea", ".vscode", "__pycache__", ".venv");
    /** The default file patterns. */
    public static final List<String> DEFAULT_FILES = List.of(".DS_Store", "*.class", "*.jar", "*.o", "*.so",
            "*.dylib", "*.pyc");

    public SearchOptions {
        skipDirs = Set.copyOf(skipDirs);
        skipFiles = List.copyOf(skipFiles);
    }

    public static SearchOptions defaults() {
        return new SearchOptions(Set.copyOf(DEFAULT_DIRS), DEFAULT_FILES);
    }

    /** True when the directory name is in the skip list. */
    public boolean skipsDir(String name) { return skipDirs.contains(name); }

    /** True when the file name equals a skip name or ends with a {@code *.suffix} pattern. */
    public boolean skipsFile(String name) {
        for (var pattern : skipFiles) {
            if (pattern.startsWith("*") ? name.endsWith(pattern.substring(1)) : name.equals(pattern)) return true;
        }
        return false;
    }
}
