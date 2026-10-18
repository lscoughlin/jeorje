package org.github.lscoughlin.jeorje.tools.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Allowed;
import org.github.lscoughlin.jeorje.tools.registry.BinaryFiles;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.github.lscoughlin.jeorje.tools.registry.Required;

/** The {@code grep} tool. It searches file content with a regular expression. */
public final class GrepTool extends RecordTool<GrepTool.Input> {
    static final int DEFAULT_LIMIT = 250;
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    static final int MAX_LINE_CHARS = 500;
    static final int MAX_CONTEXT = 20;

    /** The input of {@code grep}. */
    public record Input(
            @Required @Describe("Regular expression (Java syntax).") String pattern,
            @Describe("File or directory to search. Default is the working directory.") String path,
            @Describe("Ant-style file filter. A pattern with no / matches the file name at any depth.")
            String glob,
            @Describe("Ignore case. Default false.") Boolean ignoreCase,
            @Describe("Lines of context before each match.") Integer before,
            @Describe("Lines of context after each match.") Integer after,
            @Describe("Lines of context before and after each match. before and after override it.")
            Integer context,
            @Allowed({"content", "files", "count"})
            @Describe("content (default) shows matching lines, files lists the files, count gives counts.")
            String outputMode,
            @Describe("Number of results to skip. Default 0.") Integer offset,
            @Describe("Most results to return. Default 250.") Integer limit) {}

    private final SearchSupport support;

    public GrepTool(PermissionPolicy policy) {
        super("grep", "Search file content with a regular expression. Gives line numbers, optional "
                + "context lines, and file filters. Binary and very large files are skipped.", Input.class, true);
        this.support = new SearchSupport(policy, "grep");
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) throws Exception {
        Pattern regex;
        try {
            var flags = Boolean.TRUE.equals(input.ignoreCase()) ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            regex = Pattern.compile(input.pattern(), flags);
        } catch (PatternSyntaxException e) {
            return ToolResultData.error("Invalid regular expression: " + e.getDescription()
                    + " at index " + e.getIndex());
        }
        var mode = input.outputMode() == null ? "content" : input.outputMode();
        java.nio.file.Path root;
        try {
            root = support.root(input.path(), context);
        } catch (SearchSupport.Refused e) {
            return ToolResultData.error(e.getMessage());
        }
        var filter = input.glob() == null || input.glob().isBlank() ? null : fileFilter(input.glob());
        var before = clamp(input.before() != null ? input.before() : input.context());
        var after = clamp(input.after() != null ? input.after() : input.context());
        var offset = SearchSupport.offset(input.offset());
        var limit = SearchSupport.limit(input.limit(), DEFAULT_LIMIT, 100_000);

        var refused = new AtomicInteger();
        var files = support.files(root, context.cancel(), refused);
        var skipped = 0;
        var out = new StringBuilder();
        var seen = 0;       // results seen so far (lines, files, or counts)
        var emitted = 0;
        var truncated = false;
        var searched = 0;
        var firstGroup = true;

        search:
        for (var file : files) {
            context.cancel().throwIfCancelled();
            if (filter != null && !filter.test(SearchSupport.relative(root, file))) continue;
            var relative = SearchSupport.display(context, root, file);
            var lines = readLines(file);
            if (lines == null) {
                skipped++;
                continue;
            }
            searched++;
            var matcher = regex.matcher("");
            var hits = new ArrayList<Integer>();
            for (var i = 0; i < lines.size(); i++) {
                matcher.reset(new Interruptible(lines.get(i), context.cancel()));
                if (matcher.find()) hits.add(i);
            }
            if (hits.isEmpty()) continue;
            switch (mode) {
                case "files" -> {
                    if (seen++ < offset) continue;
                    if (emitted >= limit) { truncated = true; break search; }
                    out.append(relative).append('\n');
                    emitted++;
                }
                case "count" -> {
                    if (seen++ < offset) continue;
                    if (emitted >= limit) { truncated = true; break search; }
                    out.append(relative).append(':').append(hits.size()).append('\n');
                    emitted++;
                }
                default -> {
                    var chosen = new ArrayList<Integer>();
                    for (var hit : hits) {
                        if (seen++ < offset) continue;
                        if (emitted >= limit) { truncated = true; break; }
                        chosen.add(hit);
                        emitted++;
                    }
                    if (!chosen.isEmpty()) {
                        firstGroup = appendContent(out, relative, lines, chosen, before, after, firstGroup);
                    }
                    if (truncated) break search;
                }
            }
        }
        if (emitted == 0) out.append("No matches in ").append(searched).append(" files.\n");
        if (truncated) {
            out.append("[More results exist. Use offset ").append(offset + emitted).append(" to continue.]\n");
        }
        if (skipped > 0) out.append("[").append(skipped).append(" binary or large files skipped.]\n");
        if (refused.get() > 0) {
            out.append("[").append(refused.get()).append(" files skipped by the permission policy.]\n");
        }
        return ToolResultData.ok(out.toString());
    }

    private static int clamp(Integer value) {
        return value == null ? 0 : Math.max(0, Math.min(value, MAX_CONTEXT));
    }

    private static java.util.function.Predicate<String> fileFilter(String glob) {
        if (glob.contains("/")) {
            var pattern = new AntPattern(glob);
            return pattern::matches;
        }
        var pattern = new AntPattern(glob);
        return relative -> pattern.matches(relative.substring(relative.lastIndexOf('/') + 1));
    }

    /** Returns the lines of a text file, or null if the file is binary or too large. */
    private static List<String> readLines(java.nio.file.Path file) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) return null;
            var bytes = Files.readAllBytes(file);
            if (BinaryFiles.isBinary(bytes, false)) return null;
            var text = new String(bytes, StandardCharsets.UTF_8);
            var lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
            if (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
            return lines;
        } catch (IOException e) {
            return null;
        }
    }

    /** Writes the matches of one file with context. Returns the new value of "firstGroup". */
    private static boolean appendContent(StringBuilder out, String path, List<String> lines,
                                         List<Integer> hits, int before, int after, boolean firstGroup) {
        var hitSet = new TreeSet<>(hits);
        var show = new TreeSet<Integer>();
        for (var hit : hits) {
            for (var i = Math.max(0, hit - before); i <= Math.min(lines.size() - 1, hit + after); i++) show.add(i);
        }
        var withContext = before > 0 || after > 0;
        var previous = -2;
        var first = firstGroup;
        for (var line : show) {
            if (withContext && !first && line != previous + 1) out.append("--\n");
            first = false;
            var separator = hitSet.contains(line) ? ':' : '-';
            out.append(path).append(separator).append(line + 1).append(separator).append(shorten(lines.get(line)))
                    .append('\n');
            previous = line;
        }
        return first;
    }

    private static String shorten(String line) {
        return line.length() <= MAX_LINE_CHARS ? line : line.substring(0, MAX_LINE_CHARS) + "... [line cut]";
    }

    /** A character sequence that stops a runaway regular expression when the run is cancelled or interrupted. */
    private static final class Interruptible implements CharSequence {
        private final CharSequence inner;
        private final CancellationToken cancel;

        Interruptible(CharSequence inner, CancellationToken cancel) {
            this.inner = inner == null ? "" : inner;
            this.cancel = cancel;
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public char charAt(int index) {
            if (Thread.currentThread().isInterrupted() || cancel.isCancelled()) {
                throw new java.util.concurrent.CancellationException("grep stopped");
            }
            return inner.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Interruptible(inner.subSequence(start, end), cancel);
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
