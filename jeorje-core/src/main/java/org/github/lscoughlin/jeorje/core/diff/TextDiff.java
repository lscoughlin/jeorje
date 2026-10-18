package org.github.lscoughlin.jeorje.core.diff;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.algorithm.DiffAlgorithmI;
import com.github.difflib.algorithm.DiffAlgorithmListener;
import com.github.difflib.algorithm.myers.MyersDiff;
import com.github.difflib.algorithm.myers.MyersDiffWithLinearSpace;
import com.github.difflib.text.DiffRowGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Facade over java-diff-utils. It handles line ends, the final newline, binary content, and size
 * limits, and it applies multi-file unified diffs in memory.
 */
public final class TextDiff {

    /** The default for {@code tools.diff.maxLines}. */
    public static final int DEFAULT_MAX_LINES = 50_000;

    private static final String NONL_TOKEN = "\u0001\u0002NONL";

    /** How to treat white space when two lines are compared. */
    public enum Whitespace { NONE, TRAILING, ALL }

    /** The diff algorithm. */
    public enum Algorithm { MYERS, MYERS_LINEAR }

    /**
     * Options for diff generation.
     *
     * @param context lines of context around each change
     * @param whitespace the white space mode
     * @param algorithm the algorithm
     * @param maxLines the highest number of lines on each side
     * @param timeout the time limit for the work, or null for none
     */
    public record Options(int context, Whitespace whitespace, Algorithm algorithm, int maxLines, Duration timeout) {
        public static Options defaults() {
            return new Options(3, Whitespace.NONE, Algorithm.MYERS, DEFAULT_MAX_LINES, null);
        }

        public Options withContext(int value) {
            return new Options(value, whitespace, algorithm, maxLines, timeout);
        }

        public Options withWhitespace(Whitespace value) {
            return new Options(context, value, algorithm, maxLines, timeout);
        }

        public Options withAlgorithm(Algorithm value) {
            return new Options(context, whitespace, value, maxLines, timeout);
        }

        public Options withMaxLines(int value) {
            return new Options(context, whitespace, algorithm, value, timeout);
        }

        public Options withTimeout(Duration value) {
            return new Options(context, whitespace, algorithm, maxLines, value);
        }
    }

    /**
     * A unified diff.
     *
     * @param unified the diff text, or an empty string if the texts have no differences
     * @param added number of added lines
     * @param removed number of removed lines
     */
    public record DiffResult(String unified, int added, int removed) {
        public boolean identical() {
            return unified.isEmpty();
        }
    }

    /**
     * One changed row of the inline view.
     *
     * @param tag INSERT, DELETE, or CHANGE
     * @param oldNumber 1-based old line number, or 0 for an insert
     * @param newNumber 1-based new line number, or 0 for a delete
     * @param oldLine the old line with the changed words marked as {@code [-word-]}
     * @param newLine the new line with the changed words marked as {@code {+word+}}
     */
    public record InlineRow(Tag tag, int oldNumber, int newNumber, String oldLine, String newLine) {
        /** The kind of row. */
        public enum Tag { INSERT, DELETE, CHANGE }
    }

    private TextDiff() {
    }

    /** Generates a unified diff with the headers {@code --- oldName} and {@code +++ newName}. */
    public static DiffResult unified(String oldName, String newName, SplitText a, SplitText b, Options options) {
        var ta = tokens(a);
        var tb = tokens(b);
        var patch = computePatch(ta, tb, a, b, options);
        if (patch.getDeltas().isEmpty()) {
            return new DiffResult("", 0, 0);
        }
        var added = 0;
        var removed = 0;
        for (var delta : patch.getDeltas()) {
            removed += delta.getSource().size();
            added += delta.getTarget().size();
        }
        var raw = UnifiedDiffUtils.generateUnifiedDiff(oldName, newName, ta, patch, options.context());
        var sb = new StringBuilder();
        for (var line : raw) {
            if (line.endsWith(NONL_TOKEN)) {
                sb.append(line, 0, line.length() - NONL_TOKEN.length()).append('\n');
                sb.append(UnifiedDiffParser.NO_NEWLINE).append('\n');
            } else {
                sb.append(line).append('\n');
            }
        }
        return new DiffResult(sb.toString(), added, removed);
    }

    /** Generates the inline word-level view. Only changed rows are returned. */
    public static List<InlineRow> inline(SplitText a, SplitText b, Options options) {
        checkSize(a, b, options);
        var generator = DiffRowGenerator.create()
                .showInlineDiffs(true)
                .inlineDiffByWord(true)
                .ignoreWhiteSpaces(options.whitespace() != Whitespace.NONE)
                .lineNormalizer(Function.identity())
                .reportLinesUnchanged(true)
                .oldTag(start -> start ? "[-" : "-]")
                .newTag(start -> start ? "{+" : "+}")
                .build();
        var rows = generator.generateDiffRows(a.lines(), b.lines());
        var out = new ArrayList<InlineRow>();
        var oldNo = 1;
        var newNo = 1;
        for (var row : rows) {
            switch (row.getTag()) {
                case EQUAL -> {
                    oldNo++;
                    newNo++;
                }
                case DELETE -> out.add(new InlineRow(InlineRow.Tag.DELETE, oldNo++, 0, row.getOldLine(), ""));
                case INSERT -> out.add(new InlineRow(InlineRow.Tag.INSERT, 0, newNo++, "", row.getNewLine()));
                case CHANGE -> out.add(new InlineRow(InlineRow.Tag.CHANGE, oldNo++, newNo++,
                        row.getOldLine(), row.getNewLine()));
            }
        }
        return out;
    }

    /** Renders the inline rows as text. */
    public static String renderInline(List<InlineRow> rows) {
        var sb = new StringBuilder();
        for (var row : rows) {
            switch (row.tag()) {
                case DELETE -> sb.append("- ").append(row.oldNumber()).append(": ").append(row.oldLine()).append('\n');
                case INSERT -> sb.append("+ ").append(row.newNumber()).append(": ").append(row.newLine()).append('\n');
                case CHANGE -> {
                    sb.append("- ").append(row.oldNumber()).append(": ").append(row.oldLine()).append('\n');
                    sb.append("+ ").append(row.newNumber()).append(": ").append(row.newLine()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** Reads a unified diff for many files. */
    public static List<FileDiff> parse(String diffText) {
        return UnifiedDiffParser.parse(diffText);
    }

    /** Applies the diff of one file to the original. The original is null if the file does not exist. */
    public static FileApplyResult apply(FileDiff diff, SplitText original, int strip, ApplyOptions options) {
        var path = (options.reverse() ? diff.reversed() : diff).path(strip);
        return PatchApplier.apply(diff, original, path, options);
    }

    /**
     * Reads a diff for many files and applies every file in memory. The caller writes files only if
     * {@link #allApplied(List)} is true (or accepts conflicts).
     *
     * @param strip number of leading path parts to remove, or negative for automatic
     * @param reader gives the content of the file at a path, or null if the file does not exist
     */
    public static List<FileApplyResult> applyAll(String diffText, int strip,
            Function<String, SplitText> reader, ApplyOptions options) {
        var results = new ArrayList<FileApplyResult>();
        for (var file : parse(diffText)) {
            var path = (options.reverse() ? file.reversed() : file).path(strip);
            results.add(PatchApplier.apply(file, reader.apply(path), path, options));
        }
        return results;
    }

    /** True if every file applied with no failure and no conflict. */
    public static boolean allApplied(List<FileApplyResult> results) {
        return results.stream().allMatch(r -> r.status() == FileApplyResult.Status.APPLIED);
    }

    private static com.github.difflib.patch.Patch<String> computePatch(List<String> ta, List<String> tb,
            SplitText a, SplitText b, Options options) {
        checkSize(a, b, options);
        BiPredicate<String, String> eq = switch (options.whitespace()) {
            case NONE -> String::equals;
            case TRAILING -> (x, y) -> stripTrailing(x).equals(stripTrailing(y));
            case ALL -> (x, y) -> stripAll(x).equals(stripAll(y));
        };
        DiffAlgorithmI<String> algorithm = switch (options.algorithm()) {
            case MYERS -> new MyersDiff<>(eq);
            case MYERS_LINEAR -> new MyersDiffWithLinearSpace<>(eq);
        };
        var deadline = options.timeout() == null ? Long.MAX_VALUE : System.nanoTime() + options.timeout().toNanos();
        DiffAlgorithmListener listener = new DiffAlgorithmListener() {
            @Override
            public void diffStart() {
            }

            @Override
            public void diffStep(int value, int max) {
                if (System.nanoTime() > deadline) {
                    throw new TextDiffException(TextDiffException.Kind.TIMEOUT, "The diff took too long.");
                }
            }

            @Override
            public void diffEnd() {
            }
        };
        return DiffUtils.diff(ta, tb, algorithm, listener);
    }

    private static void checkSize(SplitText a, SplitText b, Options options) {
        if (a.lines().size() > options.maxLines() || b.lines().size() > options.maxLines()) {
            throw new TextDiffException(TextDiffException.Kind.TOO_LARGE,
                    "The input has more than " + options.maxLines() + " lines (tools.diff.maxLines).");
        }
    }

    private static List<String> tokens(SplitText t) {
        if (t.finalNewline() || t.lines().isEmpty()) {
            return t.lines();
        }
        var out = new ArrayList<>(t.lines());
        out.set(out.size() - 1, out.getLast() + NONL_TOKEN);
        return out;
    }

    private static String stripTrailing(String s) {
        var end = s.length();
        var suffix = "";
        if (s.endsWith(NONL_TOKEN)) {
            suffix = NONL_TOKEN;
            end -= NONL_TOKEN.length();
        }
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end) + suffix;
    }

    private static String stripAll(String s) {
        var sb = new StringBuilder(s.length());
        for (var i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                sb.append(s.charAt(i));
            }
        }
        return sb.toString();
    }
}
