package org.github.lscoughlin.jeorje.tools.story;

import org.github.lscoughlin.jeorje.core.implement.StoryHost;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.github.lscoughlin.jeorje.tools.registry.Required;

/** The {@code story_blocked} tool. It ends the story with a reason that the worker cannot fix. */
public final class StoryBlockedTool extends RecordTool<StoryBlockedTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "story_blocked";

    /** The input of {@code story_blocked}. */
    public record Input(@Required @Describe("Why you cannot finish the story.") String reason) {}

    private final StoryHost host;

    public StoryBlockedTool(StoryHost host) {
        super(NAME, "End your story because you cannot finish it and cannot fix the cause yourself. Give the "
                + "reason. Jeorje can start a new attempt with a new worker.", Input.class, false);
        this.host = host;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) {
        return StoryToolResults.of(host.storyBlocked(context.agent(), input.reason()));
    }
}
