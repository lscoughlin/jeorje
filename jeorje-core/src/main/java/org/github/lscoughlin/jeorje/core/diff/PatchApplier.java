package org.github.lscoughlin.jeorje.core.diff;

import java.util.ArrayList;
import java.util.List;

/** Applies the hunks of a file diff to lines in memory, with offsets, fuzz, and conflict markers. */
final class PatchApplier {

    private static final int NEARBY_CONTEXT = 3;
    private static final int NEARBY_MAX = 40;

    private PatchApplier() {
    }

    /** One line of the output, with the terminator that it keeps. */
    private record Out(String text, String end) {
    }

    private record Match(int position, int offset, int fuzz, int lead, int trail) {
    }

    static FileApplyResult apply(FileDiff diff, SplitText original, String path, ApplyOptions options) {
        var d = options.reverse() ? diff.reversed() : diff;
        var kind = d.kind();
        if (kind == FileDiff.Kind.CREATE && original != null && !original.lines().isEmpty()) {
            return fail(path, kind, "The file to create already has content.");
        }
        if (kind != FileDiff.Kind.CREATE && original == null) {
            return fail(path, kind, "The file does not exist.");
        }
        var source = original == null ? SplitText.empty() : original;
        var src = source.lines();
        var out = new ArrayList<Out>();
        var reports = new ArrayList<HunkReport>();
        var failures = new ArrayList<HunkFailure>();
        var cursor = 0;
        var finalNewline = source.finalNewline();
        var conflicted = false;
        var index = 0;
        for (var hunk : d.hunks()) {
            index++;
            var expected = Math.max(hunk.oldCount() == 0 ? hunk.oldStart() : hunk.oldStart() - 1, 0);
            expected = Math.min(Math.max(expected, cursor), src.size());
            var match = find(hunk, src, cursor, expected, options.fuzz());
            if (match == null) {
                var oldLen = hunk.oldSide().size();
                if (options.conflicts() == ApplyOptions.Conflicts.MARKERS) {
                    var end = Math.min(expected + oldLen, src.size());
                    copy(source, cursor, expected, out);
                    out.add(new Out("<<<<<<< current", source.eol()));
                    copy(source, expected, end, out);
                    out.add(new Out("=======", source.eol()));
                    for (var t : hunk.newSide()) {
                        out.add(new Out(t, source.eol()));
                    }
                    out.add(new Out(">>>>>>> patch", source.eol()));
                    cursor = end;
                    conflicted = true;
                }
                failures.add(new HunkFailure(index, hunk.header(),
                        "The context lines of the hunk do not match the file (fuzz up to "
                                + options.fuzz() + ").", expected + 1, nearby(src, expected, oldLen)));
                continue;
            }
            copy(source, cursor, match.position() - match.lead(), out);
            var lines = hunk.lines();
            var from = match.lead();
            var to = lines.size() - match.trail();
            var srcPos = match.position() - match.lead();
            // Dropped leading context keeps the file text.
            for (var k = 0; k < from; k++) {
                out.add(new Out(src.get(srcPos), source.ends().get(srcPos)));
                srcPos++;
            }
            for (var k = from; k < to; k++) {
                var line = lines.get(k);
                switch (line.op()) {
                    case ' ' -> {
                        out.add(new Out(src.get(srcPos), source.ends().get(srcPos)));
                        srcPos++;
                    }
                    case '-' -> srcPos++;
                    default -> out.add(new Out(line.text(), source.eol()));
                }
            }
            for (var k = to; k < lines.size() && srcPos < src.size(); k++) {
                out.add(new Out(src.get(srcPos), source.ends().get(srcPos)));
                srcPos++;
            }
            cursor = srcPos;
            reports.add(new HunkReport(index, hunk.header(), match.offset(), match.fuzz()));
            if ((hunk.oldNoNewline() || hunk.newNoNewline()) && cursor == src.size()) {
                finalNewline = !hunk.newNoNewline();
            }
        }
        if (!failures.isEmpty() && options.conflicts() == ApplyOptions.Conflicts.FAIL) {
            return new FileApplyResult(path, kind, FileApplyResult.Status.FAILED, null, reports, failures, null);
        }
        copy(source, cursor, src.size(), out);
        var status = conflicted ? FileApplyResult.Status.CONFLICTED : FileApplyResult.Status.APPLIED;
        if (kind == FileDiff.Kind.DELETE && !conflicted) {
            if (!out.isEmpty()) {
                return fail(path, kind, "The diff deletes the file but lines remain after it applies.");
            }
            return new FileApplyResult(path, kind, status, null, reports, failures, null);
        }
        var lines = new ArrayList<String>(out.size());
        var ends = new ArrayList<String>(out.size());
        for (var o : out) {
            lines.add(o.text());
            ends.add(o.end());
        }
        var result = new SplitText(lines, ends, source.eol(), finalNewline || lines.isEmpty());
        return new FileApplyResult(path, kind, status, result, reports, failures, null);
    }

    private static FileApplyResult fail(String path, FileDiff.Kind kind, String message) {
        return new FileApplyResult(path, kind, FileApplyResult.Status.FAILED, null, List.of(), List.of(), message);
    }

    private static void copy(SplitText source, int from, int to, List<Out> out) {
        for (var i = from; i < to; i++) {
            out.add(new Out(source.lines().get(i), source.ends().get(i)));
        }
    }

    private static Match find(Hunk hunk, List<String> src, int cursor, int expected, int maxFuzz) {
        var lines = hunk.lines();
        var leadMax = 0;
        while (leadMax < lines.size() && lines.get(leadMax).op() == ' ') {
            leadMax++;
        }
        var trailMax = 0;
        while (trailMax < lines.size() - leadMax && lines.get(lines.size() - 1 - trailMax).op() == ' ') {
            trailMax++;
        }
        for (var fuzz = 0; fuzz <= maxFuzz; fuzz++) {
            var lead = Math.min(fuzz, leadMax);
            var trail = Math.min(fuzz, trailMax);
            if (fuzz > 0 && lead == 0 && trail == 0) {
                break;
            }
            var core = new ArrayList<String>();
            for (var k = lead; k < lines.size() - trail; k++) {
                if (lines.get(k).op() != '+') {
                    core.add(lines.get(k).text());
                }
            }
            // The expected place of the first core line.
            var coreExpected = expected + lead;
            var span = src.size() + 1;
            for (var dist = 0; dist <= span; dist++) {
                for (var sign : dist == 0 ? new int[] {1} : new int[] {1, -1}) {
                    var pos = coreExpected + sign * dist;
                    if (pos - lead < cursor || pos < 0 || pos + core.size() > src.size()) {
                        continue;
                    }
                    if (matches(src, pos, core)) {
                        return new Match(pos, pos - coreExpected, fuzz, lead, trail);
                    }
                }
            }
        }
        return null;
    }

    private static boolean matches(List<String> src, int pos, List<String> core) {
        for (var k = 0; k < core.size(); k++) {
            if (!src.get(pos + k).equals(core.get(k))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> nearby(List<String> src, int expected, int oldLen) {
        var from = Math.max(0, expected - NEARBY_CONTEXT);
        var to = Math.min(src.size(), expected + oldLen + NEARBY_CONTEXT);
        to = Math.min(to, from + NEARBY_MAX);
        var out = new ArrayList<String>();
        for (var i = from; i < to; i++) {
            out.add((i + 1) + ": " + src.get(i));
        }
        return out;
    }
}
