package org.github.lscoughlin.jeorje.core.diff;

import java.util.List;

/**
 * The result of applying the diff of one file in memory.
 *
 * @param path the path of the file (see {@link FileDiff#path(int)})
 * @param kind the kind of change after any reverse
 * @param status the outcome
 * @param result the new content; null if the file is deleted or the status is FAILED
 * @param hunks the hunks that applied
 * @param failures the hunks that did not apply
 * @param error a message for an error that concerns the whole file, or null
 */
public record FileApplyResult(String path, FileDiff.Kind kind, Status status, SplitText result,
        List<HunkReport> hunks, List<HunkFailure> failures, String error) {

    /** The outcome for the file. */
    public enum Status { APPLIED, CONFLICTED, FAILED }

    public FileApplyResult {
        hunks = List.copyOf(hunks);
        failures = List.copyOf(failures);
    }

    /** True if the file is deleted by the diff. */
    public boolean deleted() {
        return status != Status.FAILED && result == null;
    }

    /** True if the file is created by the diff. */
    public boolean created() {
        return status != Status.FAILED && kind == FileDiff.Kind.CREATE;
    }
}
