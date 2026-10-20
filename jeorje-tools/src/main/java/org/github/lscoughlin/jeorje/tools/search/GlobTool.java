package org.github.lscoughlin.jeorje.tools.search;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.github.lscoughlin.jeorje.tools.registry.Required;

/** The {@code glob} tool. It finds files with an Ant-style pattern, newest first. */
public final class GlobTool extends RecordTool<GlobTool.Input> {
    static final int DEFAULT_LIMIT = 1000;

    /** The input of {@code glob}. */
    public record Input(
            @Required @Describe("Ant-style pattern, relative to path. * and ? do not match /. "
                    + "** matches any number of directories. Example: src/**/*.java") String pattern,
            @Describe("Directory to search. Default is the working directory.") String path,
            @Describe("Number of results to skip. Default 0.") Integer offset,
            @Describe("Most results to return. Default 1000.") Integer limit,
            @Describe("Also search build and editor directories, such as target and node_modules. Default false.")
            Boolean includeIgnored) {}

    private final SearchSupport support;

    public GlobTool(PermissionPolicy policy) {
        this(policy, SearchOptions.defaults());
    }

    public GlobTool(PermissionPolicy policy, SearchOptions options) {
        super("glob", "Find files with an Ant-style pattern. Results are sorted by modification time, "
                + "newest first.", Input.class, true);
        this.support = new SearchSupport(policy, "glob", options);
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) throws Exception {
        if (input.pattern().isBlank()) return ToolResultData.error("The pattern is empty.");
        Path root;
        try {
            root = support.root(input.path(), context);
        } catch (SearchSupport.Refused e) {
            return ToolResultData.error(e.getMessage());
        }
        var pattern = new AntPattern(input.pattern());
        var refused = new AtomicInteger();
        var ignored = new AtomicInteger();
        var matches = new ArrayList<Match>();
        for (var file : support.files(root, context.cancel(), refused, ignored, Boolean.TRUE.equals(input.includeIgnored()))) {
            var relative = SearchSupport.relative(root, file);
            if (pattern.matches(relative)) matches.add(new Match(SearchSupport.display(context, root, file), modified(file)));
        }
        matches.sort(Comparator.comparingLong(Match::modified).reversed().thenComparing(Match::path));
        var offset = SearchSupport.offset(input.offset());
        var limit = SearchSupport.limit(input.limit(), DEFAULT_LIMIT, 100_000);
        var out = new StringBuilder();
        var end = Math.min(matches.size(), offset + limit);
        for (var i = Math.min(offset, matches.size()); i < end; i++) out.append(matches.get(i).path()).append('\n');
        if (matches.isEmpty()) out.append("No files match.\n");
        if (end < matches.size()) {
            out.append("[").append(matches.size() - end).append(" more files match. Use offset ")
                    .append(end).append(" to continue.]\n");
        }
        if (refused.get() > 0) {
            out.append("[").append(refused.get()).append(" files skipped by the permission policy.]\n");
        }
        if (ignored.get() > 0) out.append(SearchSupport.skippedNote(ignored.get()));
        return ToolResultData.ok(out.toString());
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private record Match(String path, long modified) {}
}
