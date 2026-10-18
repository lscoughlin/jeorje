package org.github.lscoughlin.jeorje.tools.story;

import org.github.lscoughlin.jeorje.core.implement.StoryHost;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;

/** Converts the answers of the story host to tool results. */
final class StoryToolResults {
    private StoryToolResults() {}

    static ToolResultData of(StoryHost.Reply reply) {
        return reply.ok() ? ToolResultData.ok(reply.text()) : ToolResultData.error(reply.text());
    }
}
