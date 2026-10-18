package org.github.lscoughlin.jeorje.core.diff;

import java.util.List;

/**
 * The diff of one file.
 *
 * @param oldName the old file name as written, or {@code /dev/null}
 * @param newName the new file name as written, or {@code /dev/null}
 * @param kind whether the diff modifies, creates, or deletes the file
 * @param hunks the hunks, in file order
 */
public record FileDiff(String oldName, String newName, Kind kind, List<Hunk> hunks) {

    /** What the diff does to the file. */
    public enum Kind { MODIFY, CREATE, DELETE }

    public static final String DEV_NULL = "/dev/null";

    public FileDiff {
        hunks = List.copyOf(hunks);
    }

    /** The diff that undoes this diff. */
    public FileDiff reversed() {
        var swapped = hunks.stream().map(Hunk::reversed).toList();
        var swappedKind = switch (kind) {
            case CREATE -> Kind.DELETE;
            case DELETE -> Kind.CREATE;
            case MODIFY -> Kind.MODIFY;
        };
        return new FileDiff(newName, oldName, swappedKind, swapped);
    }

    /**
     * The path of the file that the diff changes.
     *
     * @param strip number of leading path parts to remove, or a negative number for automatic
     *     (remove {@code a/} and {@code b/} when both prefixes are present)
     */
    public String path(int strip) {
        var name = kind == Kind.DELETE ? oldName : newName;
        if (strip >= 0) {
            return stripParts(name, strip);
        }
        var oldOk = DEV_NULL.equals(oldName) || oldName.startsWith("a/") || oldName.startsWith("b/");
        var newOk = DEV_NULL.equals(newName) || newName.startsWith("a/") || newName.startsWith("b/");
        var bothReal = !DEV_NULL.equals(oldName) && !DEV_NULL.equals(newName);
        var distinct = oldName.charAt(0) != newName.charAt(0);
        return oldOk && newOk && (!bothReal || distinct) ? stripParts(name, 1) : name;
    }

    private static String stripParts(String name, int strip) {
        var rest = name;
        for (var i = 0; i < strip; i++) {
            var slash = rest.indexOf('/');
            if (slash < 0) {
                return rest;
            }
            rest = rest.substring(slash + 1);
        }
        return rest;
    }
}
