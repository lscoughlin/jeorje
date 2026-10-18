package org.github.lscoughlin.jeorje.core.diff;

import java.util.List;

/**
 * Report of a hunk that did not apply.
 *
 * @param index 1-based number of the hunk in the file
 * @param header the {@code @@} line
 * @param reason why the hunk did not apply
 * @param expectedLine 1-based line where the hunk expected to apply
 * @param nearbyLines the lines of the file near the expected place, each as {@code "<number>: <text>"}
 */
public record HunkFailure(int index, String header, String reason, int expectedLine, List<String> nearbyLines) {

    public HunkFailure {
        nearbyLines = List.copyOf(nearbyLines);
    }
}
