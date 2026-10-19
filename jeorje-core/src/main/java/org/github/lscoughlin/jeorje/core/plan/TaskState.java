package org.github.lscoughlin.jeorje.core.plan;

/** The state of a task. The mark in the plan file is {@code [ ]}, {@code [x]}, or {@code [!]}. */
public enum TaskState {
    OPEN(' '),
    DONE('x'),
    BLOCKED('!');

    private final char mark;

    TaskState(char mark) { this.mark = mark; }

    /** The character between the brackets. */
    public char mark() { return mark; }

    /** The state for a mark character, or null if the mark is not valid. */
    public static TaskState ofMark(char c) {
        return switch (c) {
            case ' ' -> OPEN;
            case 'x', 'X' -> DONE;
            case '!' -> BLOCKED;
            default -> null;
        };
    }
}
