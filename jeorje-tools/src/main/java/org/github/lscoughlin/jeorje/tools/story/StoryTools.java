package org.github.lscoughlin.jeorje.tools.story;

import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.core.implement.StoryHost;
import org.github.lscoughlin.jeorje.core.implement.WorkerToolsFactory;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.tools.agents.AgentAnswerTool;
import org.github.lscoughlin.jeorje.tools.agents.AgentAskTool;
import org.github.lscoughlin.jeorje.tools.agents.AgentMessageTool;
import org.github.lscoughlin.jeorje.tools.agents.AgentStatusTool;
import org.github.lscoughlin.jeorje.tools.memory.RecallTool;
import org.github.lscoughlin.jeorje.tools.worktree.SyncAbortTool;
import org.github.lscoughlin.jeorje.tools.worktree.SyncDoneTool;

/** Makes the tool set that only the workers of {@code /implement} have. */
public final class StoryTools {
    private StoryTools() {}

    /** The four story tools. */
    public static List<Tool> storyTools(StoryHost host) {
        return List.of(new TaskDoneTool(host), new StoryDoneTool(host), new StoryBlockedTool(host),
                new StoryReadTool(host));
    }

    /**
     * The factory for {@code ImplementOrchestrator}. The tools are the story tools, the agent tools
     * ({@code agent_status}, {@code agent_message}, {@code agent_ask}, {@code agent_answer}), {@code recall}, the
     * sync tools when worktrees are on, and the extra tools. Give {@code skill} and {@code advisor} as extra tools.
     * Give {@code advisor} only when an advisor model is set.
     */
    public static WorkerToolsFactory factory(List<Tool> extra) {
        return context -> {
            var tools = new ArrayList<Tool>(storyTools(context.host()));
            tools.add(new AgentStatusTool(context.runtime()));
            tools.add(new AgentMessageTool(context.runtime()));
            tools.add(new AgentAskTool(context.runtime()));
            tools.add(new AgentAnswerTool(context.runtime()));
            tools.add(new RecallTool(context.sessionId(), context.runtime()::nameOf));
            if (context.worktrees() != null) {
                tools.add(new SyncDoneTool(context.worktrees()));
                tools.add(new SyncAbortTool(context.worktrees()));
            }
            tools.addAll(extra);
            return List.copyOf(tools);
        };
    }
}
