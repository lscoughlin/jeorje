package org.github.lscoughlin.jeorje.core.instructions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Finds markdown files under a root for the command and agent registries. */
final class MarkdownItems {

    private MarkdownItems() {
    }

    /** Lists *.md files below the root (sorted), up to the depth limit. Missing roots give an empty list. */
    static List<Path> list(Path root, List<Diagnostic> diagnostics) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root, SkillRegistry.MAX_DEPTH)) {
            var out = new ArrayList<Path>();
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> {
                        for (var part : root.relativize(p)) {
                            var n = part.toString();
                            if (n.equals(".git") || n.equals("node_modules")) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .sorted().limit(SkillRegistry.MAX_DIRECTORIES).forEach(out::add);
            return out;
        } catch (IOException e) {
            diagnostics.add(Diagnostic.warning(root, "Cannot scan directory: " + e.getMessage()));
            return List.of();
        }
    }
}
