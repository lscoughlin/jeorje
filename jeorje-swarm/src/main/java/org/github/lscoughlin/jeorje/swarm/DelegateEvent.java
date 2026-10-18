package org.github.lscoughlin.jeorje.swarm;

/**
 * An event of a remote subagent. The last event has {@code last} set, an outcome, and the completion
 * summary.
 *
 * @param taskId the task id
 * @param seq the event number, counted from 1
 * @param kind the kind of event
 * @param text the progress text, the question, or the result text
 * @param questionId the question id when the kind is {@link Kind#QUESTION}
 * @param last true for the last event
 * @param outcome in the last event: {@code completed}, {@code failed}, {@code cancelled}, or {@code rejected}
 * @param summary in the last event: the completion summary
 */
public record DelegateEvent(
        String taskId,
        long seq,
        Kind kind,
        String text,
        String questionId,
        boolean last,
        String outcome,
        String summary) {

    /** The kind of an event. */
    public enum Kind {
        PROGRESS,
        QUESTION,
        RESULT,
        REJECTED,
        ERROR
    }

    public static DelegateEvent progress(String taskId, String text) {
        return new DelegateEvent(taskId, 0, Kind.PROGRESS, text, null, false, null, null);
    }

    public static DelegateEvent question(String taskId, String questionId, String text) {
        return new DelegateEvent(taskId, 0, Kind.QUESTION, text, questionId, false, null, null);
    }

    public static DelegateEvent result(String taskId, String outcome, String text, String summary) {
        return new DelegateEvent(taskId, 0, Kind.RESULT, text, null, true, outcome, summary);
    }

    public static DelegateEvent rejected(String taskId, String reason) {
        return new DelegateEvent(taskId, 0, Kind.REJECTED, reason, null, true, "rejected", reason);
    }

    public static DelegateEvent error(String taskId, String reason) {
        return new DelegateEvent(taskId, 0, Kind.ERROR, reason, null, true, "failed", reason);
    }

    DelegateEvent stamp(String newTaskId, long newSeq) {
        return new DelegateEvent(newTaskId, newSeq, kind, text, questionId, last, outcome, summary);
    }
}
