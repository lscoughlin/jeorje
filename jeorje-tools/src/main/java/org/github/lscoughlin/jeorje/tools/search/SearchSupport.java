package org.github.lscoughlin.jeorje.tools.search;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.permissions.ToolPermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared code of the search tools. Each path goes through the {@link PermissionPolicy}. The policy
 * enforces the path sandbox and the rules for secret and sensitive files. A search tool cannot
 * ask the user, so it treats ASK like DENY and skips the path.
 */
final class SearchSupport {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", ".jeorje");

    private final PermissionPolicy policy;
    private final String tool;
    private final SearchOptions options;

    SearchSupport(PermissionPolicy policy, String tool, SearchOptions options) {
        this.policy = policy;
        this.tool = tool;
        this.options = options;
    }

    SearchOptions options() { return options; }

    /** The text for the result when the tool skipped build or editor paths. */
    static String skippedNote(int skipped) {
        return "[" + skipped + " build or editor paths were skipped. Use includeIgnored to search them.]\n";
    }

    /** Thrown when a path is refused. The message goes to the model. */
    static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }

    /** True if the policy allows the tool to read this path. The audit log gets one entry for the call. */
    boolean allowed(Path path) {
        var input = JSON.createObjectNode().put("path", path.toString());
        return policy.check(new ToolUse("search-" + tool, tool, input)) == PermissionPolicy.Decision.ALLOW;
    }

    /**
     * True if the policy allows the tool to read this file of a tree walk. The policy writes an audit entry for
     * a refusal only, so that a search of thousands of files does not fill the audit log.
     */
    boolean allowedFile(Path path) {
        var input = JSON.createObjectNode().put("path", path.toString());
        return policy.check(new ToolUse(ToolPermissionPolicy.SEARCH_FILE_PREFIX + tool, tool, input))
                == PermissionPolicy.Decision.ALLOW;
    }

    /** Resolves the search root. A relative path is relative to the working directory. */
    Path root(String path, ToolContext context) throws Refused {
        var base = context.workingDirectory().toAbsolutePath().normalize();
        var resolved = path == null || path.isBlank() ? base : base.resolve(path).normalize();
        if (!allowed(resolved)) {
            throw new Refused("Refused: the permission policy does not allow " + tool + " on " + path
                    + ". The path is outside the sandbox, or it holds sensitive files.");
        }
        if (!Files.exists(resolved)) throw new Refused("Path does not exist: " + path);
        return resolved;
    }

    /** The files below a root, or the root itself if it is a file. The list is sorted by path. */
    List<Path> files(Path root, CancellationToken cancel, AtomicInteger refused, AtomicInteger skipped,
                     boolean includeIgnored) throws IOException {
        var out = new ArrayList<Path>();
        if (Files.isRegularFile(root)) {
            out.add(root);
            return out;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                cancel.throwIfCancelled();
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                var name = dir.getFileName().toString();
                if (SKIPPED_DIRECTORIES.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                if (!includeIgnored && options.skipsDir(name)) {
                    skipped.incrementAndGet();
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                cancel.throwIfCancelled();
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                if (!includeIgnored && options.skipsFile(file.getFileName().toString())) {
                    skipped.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }
                if (allowedFile(file)) {
                    out.add(file);
                } else {
                    refused.incrementAndGet();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        out.sort(null);
        return out;
    }

    /** The path of a file relative to the root, with {@code /} as separator. */
    static String relative(Path root, Path file) {
        var base = Files.isRegularFile(root) ? root.getParent() : root;
        return base.relativize(file).toString().replace('\\', '/');
    }

    /** The path to show to the model: relative to the working directory when the file is inside it. */
    static String display(ToolContext context, Path root, Path file) {
        var base = context.workingDirectory().toAbsolutePath().normalize();
        return file.startsWith(base) && !file.equals(base)
                ? base.relativize(file).toString().replace('\\', '/') : relative(root, file);
    }

    /** Clamps a limit value. */
    static int limit(Integer value, int fallback, int max) {
        if (value == null || value < 1) return fallback;
        return Math.min(value, max);
    }

    static int offset(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }
}
