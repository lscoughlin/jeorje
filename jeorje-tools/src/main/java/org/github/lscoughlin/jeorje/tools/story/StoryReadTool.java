package org.github.lscoughlin.jeorje.tools.story;

import org.github.lscoughlin.jeorje.core.implement.StoryHost;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;

/**
 * The {@code story_read} tool. With no story id it lists all stories with their state. With a story id it returns the
 * full text of that story. It reads the plan that the orchestrator holds. It never gives the plan title, the plan
 * goal, or the history of an agent.
 */
public final class StoryReadTool extends RecordTool<StoryReadTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "story_read";

    /** The input of {@code story_read}. */
    public record Input(@Describe("The story id, for example S2. Leave it out to list all stories.") String storyId) {}

    private final StoryHost host;

    public StoryReadTool(StoryHost host) {
        super(NAME, "Read the stories of the plan. With no story id: list all stories with id, title, state, "
                + "attempt, and worker. With a story id: the full text of that story with its checkboxes and "
                + "handoff. Use it to check the touches of your neighbors, to find the interface that another "
                + "story builds, and to find the worker that you must ask.", Input.class, true);
        this.host = host;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) {
        if (input.storyId() == null || input.storyId().isBlank()) return ToolResultData.ok(host.storyList());
        return host.storyText(input.storyId().strip()).map(ToolResultData::ok)
                .orElseGet(() -> ToolResultData.error("The plan has no story " + input.storyId() + ".\n"
                        + host.storyList()));
    }
}
