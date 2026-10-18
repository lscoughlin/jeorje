package org.github.lscoughlin.jeorje.tools.story;

import org.github.lscoughlin.jeorje.core.implement.StoryHost;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.github.lscoughlin.jeorje.tools.registry.Required;

/** The {@code task_done} tool. It marks one task of the story as done. Jeorje writes {@code [x]} in the plan file. */
public final class TaskDoneTool extends RecordTool<TaskDoneTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "task_done";

    /** The input of {@code task_done}. */
    public record Input(
            @Required @Describe("The id of the task, for example S1.T2.") String taskId,
            @Describe("A short note about what you did.") String note) {}

    private final StoryHost host;

    public TaskDoneTool(StoryHost host) {
        super(NAME, "Mark one task of your story as done. Call it when the task is finished, in the order of the "
                + "tasks. Jeorje writes the checkbox in the plan file.", Input.class, false);
        this.host = host;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) {
        return StoryToolResults.of(host.taskDone(context.agent(), input.taskId(), input.note()));
    }
}
