package org.github.lscoughlin.jeorje.core.implement;

import java.util.List;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.model.AgentId;

/**
 * What the story tools call. The orchestrator implements it. Every call names the worker agent, so a worker can
 * only change its own story.
 */
public interface StoryHost {
    /** The answer to a tool call. */
    record Reply(boolean ok, String text) {
        public static Reply ok(String text) { return new Reply(true, text); }

        public static Reply error(String text) { return new Reply(false, text); }
    }

    /** Marks a task of the story of the worker as done. */
    Reply taskDone(AgentId worker, String taskId, String note);

    /** Ends the story of the worker. The summary is a draft. */
    Reply storyDone(AgentId worker, String summary, List<String> filesChanged);

    /** Ends the story of the worker with a reason that the worker cannot fix. */
    Reply storyBlocked(AgentId worker, String reason);

    /** All stories with the id, the title, the state, the attempt, and the worker. */
    String storyList();

    /** The full text of one story, or empty if the plan has no such story. */
    Optional<String> storyText(String storyId);
}
