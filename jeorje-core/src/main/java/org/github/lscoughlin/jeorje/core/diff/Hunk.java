package org.github.lscoughlin.jeorje.core.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * One hunk of a unified diff.
 *
 * @param oldStart 1-based first line on the old side
 * @param oldCount number of old-side lines
 * @param newStart 1-based first line on the new side
 * @param newCount number of new-side lines
 * @param header the {@code @@} line as written
 * @param lines the lines of the hunk
 * @param oldNoNewline true if the last old-side line has no newline
 * @param newNoNewline true if the last new-side line has no newline
 */
public record Hunk(int oldStart, int oldCount, int newStart, int newCount, String header,
        List<HunkLine> lines, boolean oldNoNewline, boolean newNoNewline) {

    public Hunk {
        lines = List.copyOf(lines);
    }

    /** The texts of the old side: context and removed lines. */
    public List<String> oldSide() {
        return side('-');
    }

    /** The texts of the new side: context and added lines. */
    public List<String> newSide() {
        return side('+');
    }

    private List<String> side(char own) {
        var out = new ArrayList<String>();
        for (var line : lines) {
            if (line.op() == ' ' || line.op() == own) {
                out.add(line.text());
            }
        }
        return out;
    }

    /** The hunk that undoes this hunk. */
    public Hunk reversed() {
        var swapped = new ArrayList<HunkLine>(lines.size());
        for (var line : lines) {
            var op = switch (line.op()) {
                case '-' -> '+';
                case '+' -> '-';
                default -> line.op();
            };
            swapped.add(new HunkLine(op, line.text()));
        }
        var h = "@@ -" + range(newStart, newCount) + " +" + range(oldStart, oldCount) + " @@";
        return new Hunk(newStart, newCount, oldStart, oldCount, h, swapped, newNoNewline, oldNoNewline);
    }

    private static String range(int start, int count) {
        return count == 1 ? Integer.toString(start) : start + "," + count;
    }
}
