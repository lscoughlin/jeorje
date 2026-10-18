package org.github.lscoughlin.jeorje.ui.browser;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/** A small reader for {@code .gitignore} of the root. It supports names, globs, a leading slash, and a trailing slash. */
final class IgnoreRules {
    private record Rule(PathMatcher matcher, boolean anchored, boolean dirOnly) {}

    private final List<Rule> rules = new ArrayList<>();

    static IgnoreRules load(Path root) {
        var out = new IgnoreRules();
        var file = root.resolve(".gitignore");
        if (!Files.isRegularFile(file)) return out;
        try {
            for (var raw : Files.readAllLines(file)) {
                var line = raw.strip();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
                var dirOnly = line.endsWith("/");
                if (dirOnly) line = line.substring(0, line.length() - 1);
                var anchored = line.startsWith("/") || line.contains("/");
                if (line.startsWith("/")) line = line.substring(1);
                out.rules.add(new Rule(FileSystems.getDefault().getPathMatcher("glob:" + line), anchored, dirOnly));
            }
        } catch (IOException | RuntimeException e) {
            // an unreadable or bad file hides nothing
        }
        return out;
    }

    /** True when the path (relative to the root) is ignored by a rule or lies under an ignored directory. */
    boolean ignored(Path relative, boolean directory) {
        for (var rule : rules) {
            if (rule.dirOnly() && !directory) continue;
            if (rule.anchored()) {
                if (rule.matcher().matches(relative)) return true;
            } else if (rule.matcher().matches(relative.getFileName())) {
                return true;
            }
        }
        return false;
    }
}
