package org.github.lscoughlin.jeorje.ui.editor;

import java.util.Arrays;

/**
 * The text of one document, held in a gap buffer. The buffer uses {@code \n} as the only line end. A line index
 * is built on demand after a change.
 */
public final class TextBuffer {
    private char[] data;
    private int gapStart;
    private int gapEnd;
    private int[] starts = new int[] {0};
    private int lineCount = 1;
    private boolean dirty;
    private int version;

    public TextBuffer() {
        this("");
    }

    public TextBuffer(String text) {
        data = new char[Math.max(256, text.length() * 2)];
        text.getChars(0, text.length(), data, 0);
        gapStart = text.length();
        gapEnd = data.length;
        dirty = true;
    }

    /** The number of chars in the text. */
    public int length() {
        return data.length - (gapEnd - gapStart);
    }

    /** A number that changes with each change of the text. */
    public int version() {
        return version;
    }

    public char charAt(int index) {
        if (index < 0 || index >= length()) {
            throw new IndexOutOfBoundsException("index " + index + ", length " + length());
        }
        return index < gapStart ? data[index] : data[index + (gapEnd - gapStart)];
    }

    public String substring(int from, int to) {
        if (from < 0 || to > length() || from > to) {
            throw new IndexOutOfBoundsException("range " + from + ".." + to + ", length " + length());
        }
        var sb = new StringBuilder(to - from);
        for (var i = from; i < to; i++) {
            sb.append(charAt(i));
        }
        return sb.toString();
    }

    public String text() {
        return substring(0, length());
    }

    /** Inserts text at a position. */
    public void insert(int pos, String text) {
        if (pos < 0 || pos > length()) {
            throw new IndexOutOfBoundsException("position " + pos + ", length " + length());
        }
        if (text.isEmpty()) {
            return;
        }
        moveGap(pos);
        if (gapEnd - gapStart < text.length()) {
            grow(text.length());
        }
        text.getChars(0, text.length(), data, gapStart);
        gapStart += text.length();
        changed();
    }

    /** Deletes {@code count} chars from a position. */
    public void delete(int pos, int count) {
        if (pos < 0 || count < 0 || pos + count > length()) {
            throw new IndexOutOfBoundsException("range " + pos + "+" + count + ", length " + length());
        }
        if (count == 0) {
            return;
        }
        moveGap(pos);
        gapEnd += count;
        changed();
    }

    private void changed() {
        dirty = true;
        version++;
    }

    private void moveGap(int pos) {
        if (pos < gapStart) {
            var n = gapStart - pos;
            System.arraycopy(data, pos, data, gapEnd - n, n);
            gapStart = pos;
            gapEnd -= n;
        } else if (pos > gapStart) {
            var n = pos - gapStart;
            System.arraycopy(data, gapEnd, data, gapStart, n);
            gapStart += n;
            gapEnd += n;
        }
    }

    private void grow(int needed) {
        var tail = data.length - gapEnd;
        var size = Math.max(data.length * 2, length() + needed + 256);
        var bigger = new char[size];
        System.arraycopy(data, 0, bigger, 0, gapStart);
        System.arraycopy(data, gapEnd, bigger, size - tail, tail);
        data = bigger;
        gapEnd = size - tail;
    }

    private void index() {
        if (!dirty) {
            return;
        }
        var n = length();
        var list = new int[Math.max(16, lineCount)];
        var count = 0;
        list[count++] = 0;
        for (var i = 0; i < n; i++) {
            if (charAt(i) == '\n') {
                if (count == list.length) {
                    list = Arrays.copyOf(list, list.length * 2);
                }
                list[count++] = i + 1;
            }
        }
        starts = list;
        lineCount = count;
        dirty = false;
    }

    /** The number of lines. An empty text has one line. A final {@code \n} adds an empty last line. */
    public int lineCount() {
        index();
        return lineCount;
    }

    /** The offset of the first char of a line. */
    public int lineStart(int line) {
        index();
        return starts[Math.clamp(line, 0, lineCount - 1)];
    }

    /** The offset after the last char of a line, before its {@code \n}. */
    public int lineEnd(int line) {
        index();
        var l = Math.clamp(line, 0, lineCount - 1);
        return l == lineCount - 1 ? length() : starts[l + 1] - 1;
    }

    /** The line that holds an offset. */
    public int lineOf(int offset) {
        index();
        var lo = 0;
        var hi = lineCount - 1;
        while (lo < hi) {
            var mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** The text of a line with no line end. */
    public String line(int line) {
        return substring(lineStart(line), lineEnd(line));
    }
}
