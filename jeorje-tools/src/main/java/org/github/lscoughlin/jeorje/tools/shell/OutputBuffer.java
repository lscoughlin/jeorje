package org.github.lscoughlin.jeorje.tools.shell;

/**
 * Holds the output of a process. It keeps the start and the end. The middle is dropped when the
 * output is longer than the two limits together.
 */
final class OutputBuffer {
    private final int headMax;
    private final int tailMax;
    private final StringBuilder head = new StringBuilder();
    private final StringBuilder tail = new StringBuilder();
    private long total;

    OutputBuffer(int headMax, int tailMax) {
        this.headMax = headMax;
        this.tailMax = tailMax;
    }

    synchronized void append(String text) {
        total += text.length();
        var rest = text;
        if (head.length() < headMax) {
            var take = Math.min(headMax - head.length(), rest.length());
            head.append(rest, 0, take);
            rest = rest.substring(take);
        }
        if (rest.isEmpty()) return;
        tail.append(rest);
        if (tail.length() > tailMax * 2) trimTail();
    }

    private void trimTail() {
        tail.delete(0, tail.length() - tailMax);
    }

    synchronized long total() {
        return total;
    }

    /** The text with a note in place of the dropped middle. */
    synchronized String render() {
        if (tail.length() > tailMax) trimTail();
        var kept = head.length() + tail.length();
        var dropped = total - kept;
        if (dropped <= 0) return head.toString() + tail;
        return head + "\n[... " + dropped + " characters of output omitted ...]\n" + tail;
    }

    /** Returns the text like {@link #render()} and then clears the buffer. */
    synchronized String drain() {
        var text = render();
        head.setLength(0);
        tail.setLength(0);
        total = 0;
        return text;
    }
}
