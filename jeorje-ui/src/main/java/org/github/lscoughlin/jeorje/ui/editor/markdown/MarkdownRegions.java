package org.github.lscoughlin.jeorje.ui.editor.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Records the region type of each line of a Markdown file: text, fence line, fenced content, or front matter.
 *
 * <p>Each line stores the scanner state after the line. An edit therefore scans again from the first changed
 * line and stops when a line below the edit has the same type and state as before.
 */
public final class MarkdownRegions {

    /** The region type of a line. */
    public enum Type { TEXT, FENCE_OPEN, CONTENT, FENCE_CLOSE, FRONT_MATTER }

    /**
     * An open fence.
     *
     * @param ch      the fence character, a backtick or a tilde
     * @param length  the number of fence characters
     * @param column  the column of the first fence character in the opening line
     * @param cap     the largest indent of a closing line
     * @param info    the info string of the opening line
     */
    public record Fence(char ch, int length, int column, int cap, String info) {
    }

    private record State(Fence fence, int listIndent) {
        static final State START = new State(null, 0);
    }

    private static final class Entry {
        String text;
        Type type;
        State after;

        Entry(String text) {
            this.text = text;
        }
    }

    /** Front matter is only looked for in this many lines. */
    static final int FRONT_MATTER_LIMIT = 500;

    private static final Pattern LIST_MARKER = Pattern.compile("([-*+]|\\d{1,9}[.)])([ \\t]+|$)");
    private static final Pattern YAML_KEY =
            Pattern.compile("^(?:[A-Za-z0-9_.$-][^:#]*|\"[^\"]*\"|'[^']*'):(?:\\s.*)?$");

    private final List<Entry> entries = new ArrayList<>();
    private int lastScanStart;
    private int lastScanEnd;

    private MarkdownRegions() {
    }

    /** Scans a whole document. */
    public static MarkdownRegions of(List<String> lines) {
        var regions = new MarkdownRegions();
        for (var line : lines) {
            regions.entries.add(new Entry(line));
        }
        regions.scanFrom(0, -1);
        return regions;
    }

    // ---- queries ----

    public int size() {
        return entries.size();
    }

    public String line(int index) {
        return entries.get(index).text;
    }

    public Type type(int index) {
        return entries.get(index).type;
    }

    /** The first line that the last update scanned. */
    public int lastScanStart() {
        return lastScanStart;
    }

    /** The line after the last line that the last update scanned. */
    public int lastScanEnd() {
        return lastScanEnd;
    }

    /** The opening line of the block that holds the line. The line must be a fence line or fenced content. */
    public int blockStart(int index) {
        var i = index;
        while (i > 0 && type(i) != Type.FENCE_OPEN) {
            i--;
        }
        return i;
    }

    /** The last line of the block that holds the line: the closing fence, or the last line of the file. */
    public int blockEnd(int index) {
        var i = index;
        while (i < size() - 1 && type(i) != Type.FENCE_CLOSE) {
            i++;
        }
        return i;
    }

    /** The fence of a fence line or of fenced content. */
    public Fence fence(int index) {
        return entries.get(blockStart(index)).after.fence();
    }

    /** The types of all lines. */
    public List<Type> types() {
        return entries.stream().map(e -> e.type).toList();
    }

    /** A text that describes the types and the fences of all lines. Two equal scans give equal text. */
    public String snapshot() {
        var sb = new StringBuilder();
        for (var entry : entries) {
            sb.append(entry.type).append(' ').append(entry.after).append('\n');
        }
        return sb.toString();
    }

    // ---- update ----

    /** Replaces {@code removed} lines at {@code first} with the inserted lines and updates the map. */
    public void replace(int first, int removed, List<String> inserted) {
        if (first < 0 || removed < 0 || first + removed > entries.size()) {
            throw new IndexOutOfBoundsException("first=" + first + " removed=" + removed + " size=" + entries.size());
        }
        var oldFrontMatter = frontMatterEnd();
        entries.subList(first, first + removed).clear();
        var fresh = new ArrayList<Entry>(inserted.size());
        for (var line : inserted) {
            fresh.add(new Entry(line));
        }
        entries.addAll(first, fresh);
        var start = first;
        if (first <= FRONT_MATTER_LIMIT + 1 && (oldFrontMatter > 0 || isDelimiter(0))) {
            start = 0;
        }
        scanFrom(start, first + inserted.size());
    }

    /**
     * Scans from {@code start}. Lines before {@code changedEnd} are new. From {@code changedEnd}, the scan stops
     * at a line with the same type and state as before. A negative {@code changedEnd} scans to the end.
     */
    private void scanFrom(int start, int changedEnd) {
        lastScanStart = start;
        var state = start == 0 ? State.START : entries.get(start - 1).after;
        var i = start;
        if (start == 0) {
            var fm = frontMatterEnd();
            for (; i < fm; i++) {
                var entry = entries.get(i);
                entry.type = Type.FRONT_MATTER;
                entry.after = State.START;
            }
        }
        for (; i < entries.size(); i++) {
            var entry = entries.get(i);
            var oldType = entry.type;
            var oldAfter = entry.after;
            var scanned = scanLine(entry.text, state);
            entry.type = scanned.type();
            entry.after = scanned.after();
            state = scanned.after();
            if (changedEnd >= 0 && i >= changedEnd && oldType != null && oldType == entry.type
                    && oldAfter.equals(entry.after)) {
                i++;
                break;
            }
        }
        lastScanEnd = i;
    }

    private record Scanned(Type type, State after) {
    }

    private static Scanned scanLine(String text, State before) {
        var fence = before.fence();
        if (fence != null) {
            if (closes(text, fence)) {
                return new Scanned(Type.FENCE_CLOSE, new State(null, before.listIndent()));
            }
            return new Scanned(Type.CONTENT, before);
        }
        var open = opens(text, before.listIndent());
        if (open != null) {
            return new Scanned(Type.FENCE_OPEN, new State(open, before.listIndent()));
        }
        return new Scanned(Type.TEXT, new State(null, listIndentAfter(text, before.listIndent())));
    }

    // ---- fence rules ----

    /** The offset after the block quote markers at the start of the line. */
    private static int quoteEnd(String text) {
        var i = 0;
        while (true) {
            var j = i;
            var spaces = 0;
            while (j < text.length() && text.charAt(j) == ' ') {
                j++;
                spaces++;
            }
            if (spaces <= 3 && j < text.length() && text.charAt(j) == '>') {
                j++;
                if (j < text.length() && text.charAt(j) == ' ') {
                    j++;
                }
                i = j;
            } else {
                return i;
            }
        }
    }

    private static int indentOf(String text, int from) {
        var width = 0;
        for (var i = from; i < text.length(); i++) {
            var c = text.charAt(i);
            if (c == ' ') {
                width++;
            } else if (c == '\t') {
                width += 4 - (width % 4);
            } else {
                break;
            }
        }
        return width;
    }

    private static int skipBlanks(String text, int from) {
        var i = from;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private static Fence opens(String text, int listIndent) {
        var q = quoteEnd(text);
        var p = skipBlanks(text, q);
        var indent = indentOf(text, q);
        var viaMarker = false;
        while (true) {
            var m = LIST_MARKER.matcher(text).region(p, text.length());
            if (!m.lookingAt() || m.end() == p) {
                break;
            }
            p = m.end();
            viaMarker = true;
        }
        if (!viaMarker && indent > 3 + listIndent) {
            return null;
        }
        if (p >= text.length()) {
            return null;
        }
        var ch = text.charAt(p);
        if (ch != '`' && ch != '~') {
            return null;
        }
        var run = 0;
        while (p + run < text.length() && text.charAt(p + run) == ch) {
            run++;
        }
        if (run < 3) {
            return null;
        }
        var rest = text.substring(p + run);
        if (ch == '`' && rest.indexOf('`') >= 0) {
            return null;
        }
        return new Fence(ch, run, p, p - q + 3, rest.strip());
    }

    private static boolean closes(String text, Fence fence) {
        var q = quoteEnd(text);
        var p = skipBlanks(text, q);
        if (indentOf(text, q) > fence.cap()) {
            return false;
        }
        var run = 0;
        while (p + run < text.length() && text.charAt(p + run) == fence.ch()) {
            run++;
        }
        if (run < fence.length()) {
            return false;
        }
        return text.substring(p + run).isBlank();
    }

    private static int listIndentAfter(String text, int current) {
        if (text.isBlank()) {
            return current;
        }
        var q = quoteEnd(text);
        var p = skipBlanks(text, q);
        var m = LIST_MARKER.matcher(text).region(p, text.length());
        if (m.lookingAt() && m.end() > p && p < text.length()) {
            return Math.max(1, m.end() - q);
        }
        if (indentOf(text, q) < current) {
            return 0;
        }
        return current;
    }

    // ---- front matter ----

    private boolean isDelimiter(int index) {
        return index < entries.size() && entries.get(index).text.stripTrailing().equals("---");
    }

    /** The number of lines of the front matter at the top, or 0. The block must be a YAML mapping. */
    private int frontMatterEnd() {
        if (entries.isEmpty() || !isDelimiter(0)) {
            return 0;
        }
        var limit = Math.min(entries.size(), FRONT_MATTER_LIMIT);
        for (var j = 1; j < limit; j++) {
            var text = entries.get(j).text.stripTrailing();
            if (text.equals("---") || text.equals("...")) {
                return isYamlMapping(1, j) ? j + 1 : 0;
            }
        }
        return 0;
    }

    private boolean isYamlMapping(int from, int to) {
        var sawKey = false;
        for (var i = from; i < to; i++) {
            var text = entries.get(i).text.stripTrailing();
            if (text.isBlank() || text.stripLeading().startsWith("#")) {
                continue;
            }
            if (YAML_KEY.matcher(text).matches()) {
                sawKey = true;
            } else if (!sawKey || !(Character.isWhitespace(text.charAt(0)) || text.startsWith("-"))) {
                return false;
            }
        }
        return sawKey;
    }
}
