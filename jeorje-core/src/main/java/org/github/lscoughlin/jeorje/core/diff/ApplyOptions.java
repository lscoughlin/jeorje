package org.github.lscoughlin.jeorje.core.diff;

/**
 * Options for applying a diff in memory.
 *
 * @param fuzz the highest number of context lines at each end of a hunk that can differ (0 to 3)
 * @param reverse true to undo the diff
 * @param conflicts what to do with a hunk that does not apply
 */
public record ApplyOptions(int fuzz, boolean reverse, Conflicts conflicts) {

    /** What to do with a hunk that does not apply. */
    public enum Conflicts { FAIL, MARKERS }

    public ApplyOptions {
        if (fuzz < 0 || fuzz > 3) {
            throw new IllegalArgumentException("fuzz must be 0 to 3");
        }
    }

    public static ApplyOptions defaults() {
        return new ApplyOptions(2, false, Conflicts.FAIL);
    }

    public ApplyOptions withFuzz(int value) {
        return new ApplyOptions(value, reverse, conflicts);
    }

    public ApplyOptions withReverse(boolean value) {
        return new ApplyOptions(fuzz, value, conflicts);
    }

    public ApplyOptions withConflicts(Conflicts value) {
        return new ApplyOptions(fuzz, reverse, value);
    }
}
