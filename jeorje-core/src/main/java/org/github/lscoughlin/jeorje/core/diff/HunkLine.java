package org.github.lscoughlin.jeorje.core.diff;

/**
 * One line of a hunk.
 *
 * @param op {@code ' '} for context, {@code '-'} for removed, {@code '+'} for added
 * @param text the text of the line, with no terminator
 */
public record HunkLine(char op, String text) {
}
