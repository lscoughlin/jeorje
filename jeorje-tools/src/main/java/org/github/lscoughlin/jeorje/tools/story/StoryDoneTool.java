package org.github.lscoughlin.jeorje.tools.story;

import java.util.List;
import org.github.lscoughlin.jeorje.core.implement.StoryHost;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.github.lscoughlin.jeorje.tools.registry.Required;

/** The {@code story_done} tool. It ends the story with a draft of the completion summary. */
public final class StoryDoneTool extends RecordTool<StoryDoneTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "story_done";

    /** The input of {@code story_done}. */
    public record Input(
            @Required @Describe("A draft of the completion summary with these labeled parts: Outcome, Done, Files, "
                    + "Branch, Decisions, Verification, Open issues, Keywords. At most 300 words.") String summary,
            @Describe("The files that you created, changed, or deleted.") List<String> filesChanged) {}

    private final StoryHost host;

    public StoryDoneTool(StoryHost host) {
        super(NAME, "End your story. Call it after you ran the verification of the story and all acceptance "
                + "criteria are true, and after you called task_done for every task. The summary is a draft. "
                + "After this call you end your turn. Jeorje then merges the source into your branch, runs the "
                + "verification, asks for a review, and merges your branch back. You write the final summary "
                + "after the merge.", Input.class, false);
        this.host = host;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) {
        return StoryToolResults.of(host.storyDone(context.agent(), input.summary(), input.filesChanged()));
    }
}
