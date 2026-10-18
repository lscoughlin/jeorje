package org.github.lscoughlin.jeorje.core.diff;

/**
 * Report of a hunk that applied.
 *
 * @param index 1-based number of the hunk in the file
 * @param header the {@code @@} line
 * @param offset the distance in lines between the expected place and the place where the hunk applied
 * @param fuzz the number of context lines at each end that did not need to match
 */
public record HunkReport(int index, String header, int offset, int fuzz) {
}
