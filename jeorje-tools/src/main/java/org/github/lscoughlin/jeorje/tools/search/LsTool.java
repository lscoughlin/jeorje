package org.github.lscoughlin.jeorje.tools.search;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;

/** The {@code ls} tool. It lists one directory. */
public final class LsTool extends RecordTool<LsTool.Input> {
    static final int DEFAULT_LIMIT = 500;

    /** The input of {@code ls}. */
    public record Input(
            @Describe("Directory to list. Default is the working directory.") String path,
            @Describe("Number of entries to skip. Default 0.") Integer offset,
            @Describe("Most entries to return. Default 500.") Integer limit) {}

    private final SearchSupport support;

    public LsTool(PermissionPolicy policy) {
        super("ls", "List the entries of a directory. Directories come first and end with /. "
                + "Files show their size in bytes.", Input.class, true);
        this.support = new SearchSupport(policy, "ls");
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) throws Exception {
        Path root;
        try {
            root = support.root(input.path(), context);
        } catch (SearchSupport.Refused e) {
            return ToolResultData.error(e.getMessage());
        }
        if (!Files.isDirectory(root)) return ToolResultData.error("Not a directory: " + input.path());
        var entries = new ArrayList<Path>();
        var hidden = 0;
        try (var stream = Files.newDirectoryStream(root)) {
            for (var entry : stream) {
                context.cancel().throwIfCancelled();
                if (support.allowed(entry)) entries.add(entry); else hidden++;
            }
        } catch (IOException e) {
            return ToolResultData.error("Cannot list " + root + ": " + e.getMessage());
        }
        entries.sort(Comparator.<Path, Boolean>comparing(p -> !Files.isDirectory(p))
                .thenComparing(p -> p.getFileName().toString()));
        var offset = SearchSupport.offset(input.offset());
        var limit = SearchSupport.limit(input.limit(), DEFAULT_LIMIT, 10_000);
        var out = new StringBuilder();
        var end = Math.min(entries.size(), offset + limit);
        for (var i = Math.min(offset, entries.size()); i < end; i++) {
            var entry = entries.get(i);
            var name = entry.getFileName().toString();
            if (Files.isDirectory(entry)) {
                out.append(name).append("/\n");
            } else {
                long size;
                try {
                    size = Files.size(entry);
                } catch (IOException e) {
                    size = -1;
                }
                out.append(name).append("  ").append(size).append('\n');
            }
        }
        if (entries.isEmpty()) out.append("(empty)\n");
        if (end < entries.size()) {
            out.append("[").append(entries.size() - end).append(" more entries. Use offset ")
                    .append(end).append(" to continue.]\n");
        }
        if (hidden > 0) out.append("[").append(hidden).append(" entries hidden by the permission policy.]\n");
        return ToolResultData.ok(out.toString());
    }
}
