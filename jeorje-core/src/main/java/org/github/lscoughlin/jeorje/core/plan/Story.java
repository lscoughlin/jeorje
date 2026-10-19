package org.github.lscoughlin.jeorje.core.plan;

import java.util.List;
import java.util.Objects;

/**
 * One story of a plan. Text parts that the story does not have are null or empty.
 *
 * @param id the story id, for example {@code S1}
 * @param title the title
 * @param goal the goal
 * @param dependsOn the ids of the stories that must finish first
 * @param touches the paths that the story changes
 * @param model the model name, or null for the default
 * @param context the context part
 * @param acceptance the acceptance criteria
 * @param verification the verification part, or null
 * @param tasks the tasks, in order
 * @param handoff the handoff section, or null
 * @param previousAttempt the previous-attempt section, or null
 * @param span the lines of the story in the source file, or null if it was not parsed
 * @param handoffSpan the lines of the handoff section in the source file, or null
 */
public record Story(String id, String title, String goal, List<String> dependsOn, List<String> touches,
                    String model, String context, String acceptance, String verification, List<Task> tasks,
                    String handoff, String previousAttempt, Span span, Span handoffSpan) {
    public Story {
        Objects.requireNonNull(id, "id");
        title = title == null ? "" : title;
        goal = goal == null ? "" : goal;
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        touches = touches == null ? List.of() : List.copyOf(touches);
        context = context == null ? "" : context;
        acceptance = acceptance == null ? "" : acceptance;
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    /** A story that was not parsed from a file. */
    public Story(String id, String title, String goal, List<String> dependsOn, List<String> touches,
                 String model, String context, String acceptance, String verification, List<Task> tasks) {
        this(id, title, goal, dependsOn, touches, model, context, acceptance, verification, tasks,
                null, null, null, null);
    }

    public Story withHandoff(String text) {
        return new Story(id, title, goal, dependsOn, touches, model, context, acceptance, verification, tasks,
                text, previousAttempt, span, handoffSpan);
    }

    public Story withPreviousAttempt(String text) {
        return new Story(id, title, goal, dependsOn, touches, model, context, acceptance, verification, tasks,
                handoff, text, span, handoffSpan);
    }

    public Story withTasks(List<Task> next) {
        return new Story(id, title, goal, dependsOn, touches, model, context, acceptance, verification, next,
                handoff, previousAttempt, span, handoffSpan);
    }

    /** The same story with one task in another state. An unknown task id changes nothing. */
    public Story withTaskState(String taskId, TaskState state) {
        return withTasks(tasks.stream().map(t -> t.id().equals(taskId) ? t.withState(state) : t).toList());
    }

    /** True if every task is done. A story with no tasks is not done. */
    public boolean done() {
        return !tasks.isEmpty() && tasks.stream().allMatch(t -> t.state() == TaskState.DONE);
    }

    public boolean blocked() {
        return tasks.stream().anyMatch(t -> t.state() == TaskState.BLOCKED);
    }

    /** The text of the story as the plan file shows it. */
    public String render() { return PlanWriter.renderStory(this); }
}
