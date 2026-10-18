package org.github.lscoughlin.jeorje.core.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Reads a unified diff for many files. Renames, mode changes, and binary patches are errors. */
final class UnifiedDiffParser {

    static final String NO_NEWLINE = "\\ No newline at end of file";
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    private UnifiedDiffParser() {
    }

    static List<FileDiff> parse(String text) {
        var raw = text.split("\r?\n", -1);
        var lines = new ArrayList<String>(List.of(raw));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        var files = new ArrayList<FileDiff>();
        var i = 0;
        String oldName = null;
        String newName = null;
        List<Hunk> hunks = null;
        var newFileMode = false;
        var deletedFileMode = false;
        while (i < lines.size()) {
            var line = lines.get(i);
            if (line.startsWith("diff ")) {
                finish(files, oldName, newName, hunks, newFileMode, deletedFileMode);
                oldName = null;
                newName = null;
                hunks = null;
                newFileMode = false;
                deletedFileMode = false;
                i++;
            } else if (line.startsWith("rename ") || line.startsWith("copy ")
                    || line.startsWith("old mode ") || line.startsWith("new mode ")
                    || line.startsWith("similarity index ") || line.startsWith("dissimilarity index ")) {
                throw new TextDiffException(TextDiffException.Kind.UNSUPPORTED,
                        "Renames, copies, and mode changes are not supported: " + line);
            } else if (line.startsWith("Binary files ") || line.startsWith("GIT binary patch")) {
                throw new TextDiffException(TextDiffException.Kind.BINARY,
                        "The diff has a binary change: " + line);
            } else if (line.startsWith("new file mode")) {
                newFileMode = true;
                i++;
            } else if (line.startsWith("deleted file mode")) {
                deletedFileMode = true;
                i++;
            } else if (line.startsWith("--- ") && i + 1 < lines.size() && lines.get(i + 1).startsWith("+++ ")) {
                if (hunks != null) {
                    finish(files, oldName, newName, hunks, newFileMode, deletedFileMode);
                    newFileMode = false;
                    deletedFileMode = false;
                }
                oldName = fileName(line.substring(4));
                newName = fileName(lines.get(i + 1).substring(4));
                hunks = new ArrayList<>();
                i += 2;
            } else if (line.startsWith("@@")) {
                if (hunks == null) {
                    throw new TextDiffException(TextDiffException.Kind.MALFORMED,
                            "A hunk has no file header (--- and +++ lines): " + line);
                }
                i = parseHunk(lines, i, hunks);
            } else {
                i++;
            }
        }
        finish(files, oldName, newName, hunks, newFileMode, deletedFileMode);
        if (files.isEmpty()) {
            throw new TextDiffException(TextDiffException.Kind.MALFORMED, "The diff has no file with hunks.");
        }
        return files;
    }

    private static void finish(List<FileDiff> files, String oldName, String newName, List<Hunk> hunks,
            boolean newFileMode, boolean deletedFileMode) {
        if (hunks == null) {
            return;
        }
        if (hunks.isEmpty()) {
            throw new TextDiffException(TextDiffException.Kind.MALFORMED,
                    "The file " + newName + " has no hunks.");
        }
        var kind = FileDiff.DEV_NULL.equals(oldName) || newFileMode ? FileDiff.Kind.CREATE
                : FileDiff.DEV_NULL.equals(newName) || deletedFileMode ? FileDiff.Kind.DELETE
                : FileDiff.Kind.MODIFY;
        files.add(new FileDiff(oldName, newName, kind, hunks));
    }

    private static String fileName(String raw) {
        var tab = raw.indexOf('\t');
        var name = tab >= 0 ? raw.substring(0, tab) : raw;
        return name.strip();
    }

    private static int parseHunk(List<String> lines, int start, List<Hunk> out) {
        var header = lines.get(start);
        var m = HUNK.matcher(header);
        if (!m.matches()) {
            throw new TextDiffException(TextDiffException.Kind.MALFORMED, "Bad hunk header: " + header);
        }
        var oldStart = Integer.parseInt(m.group(1));
        var oldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        var newStart = Integer.parseInt(m.group(3));
        var newCount = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
        var body = new ArrayList<HunkLine>();
        var oldSeen = 0;
        var newSeen = 0;
        var oldNoNl = false;
        var newNoNl = false;
        var i = start + 1;
        while (i < lines.size()) {
            var line = lines.get(i);
            if (line.startsWith("\\")) {
                if (!body.isEmpty()) {
                    switch (body.getLast().op()) {
                        case '-' -> oldNoNl = true;
                        case '+' -> newNoNl = true;
                        default -> {
                            oldNoNl = true;
                            newNoNl = true;
                        }
                    }
                }
                i++;
                continue;
            }
            if (oldSeen >= oldCount && newSeen >= newCount) {
                break;
            }
            if (line.startsWith("@@") || line.startsWith("diff ")) {
                break;
            }
            var op = line.isEmpty() ? ' ' : line.charAt(0);
            switch (op) {
                case ' ' -> {
                    body.add(new HunkLine(' ', line.isEmpty() ? "" : line.substring(1)));
                    oldSeen++;
                    newSeen++;
                }
                case '-' -> {
                    body.add(new HunkLine('-', line.substring(1)));
                    oldSeen++;
                }
                case '+' -> {
                    body.add(new HunkLine('+', line.substring(1)));
                    newSeen++;
                }
                default -> throw new TextDiffException(TextDiffException.Kind.MALFORMED,
                        "Bad line in hunk " + header + ": " + line);
            }
            i++;
        }
        out.add(new Hunk(oldStart, oldSeen, newStart, newSeen, header, body, oldNoNl, newNoNl));
        return i;
    }
}
