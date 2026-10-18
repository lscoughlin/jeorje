package org.github.lscoughlin.jeorje.core.implement;

/** An event of the orchestrator. The UI uses these events for the status line and the chat view. */
public sealed interface ImplementEvent {

    /** The plan is loaded and the run starts. */
    record RunStarted(String plan, int stories, int open) implements ImplementEvent {}

    /** A worker starts a story. */
    record StoryStarted(String storyId, int attempt, String worker, String branch) implements ImplementEvent {}

    /** The state of a story changed. */
    record StoryChanged(String storyId, StoryState state, String detail) implements ImplementEvent {}

    /** A worker finished a task. */
    record TaskFinished(String storyId, String taskId, String note) implements ImplementEvent {}

    /** The review of a story ended. */
    record Reviewed(String storyId, boolean approved, String problems) implements ImplementEvent {}

    /** A message for the user. */
    record Notice(String text) implements ImplementEvent {}

    /** The run is paused, resumed, or stopped. */
    record RunControl(String action) implements ImplementEvent {}

    /** The run ended. */
    record RunFinished(ImplementReport report) implements ImplementEvent {}
}
