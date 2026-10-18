package org.github.lscoughlin.jeorje.core.subagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import org.github.lscoughlin.jeorje.core.agent.Agent;
import org.github.lscoughlin.jeorje.core.agent.AgentInput;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.Tool;

/** The runtime record of one agent: a root agent or a subagent. All mutable fields are guarded by the runtime. */
final class AgentNode {
    /** One item in the inbox: a message or a question. */
    record InboxItem(String text, Question question) {}

    /** An internal wait of the agent. */
    record InternalWait(AgentId holder, String reason) {}

    final AgentId id;
    final String name;
    final AgentNode parent;
    final int depth;
    final SpawnRequest request;
    final CancellationToken token;
    final List<Tool> tools;
    final PermissionPolicy policy;
    volatile Consumer<AgentInput> input;
    volatile IntSupplier turn;
    final Set<String> droppedTools;
    final CompletableFuture<Completion> result = new CompletableFuture<>();
    final List<AgentNode> children = new CopyOnWriteArrayList<>();
    final List<InboxItem> inbox = new ArrayList<>();
    final List<InternalWait> internalWaits = new ArrayList<>();

    volatile AgentLifecycle state;
    AgentLifecycle resumeState = AgentLifecycle.RUNNING;
    volatile String waitingFor;
    volatile String lastOutput = "";
    volatile String failureReason;
    volatile Agent agent;
    volatile String branch;
    /** The handle of the task on a peer, or null for a local agent. */
    volatile RemoteSpawner.Task remote;
    volatile String remotePeer;
    boolean ending;
    final StringBuilder tail = new StringBuilder();
    Question openAsk;
    int asks;
    int summaries;
    final boolean root;

    AgentNode(AgentId id, String name, AgentNode parent, SpawnRequest request, CancellationToken token,
              List<Tool> tools, PermissionPolicy policy, Consumer<AgentInput> input, IntSupplier turn,
              Set<String> droppedTools, boolean root) {
        this.id = id;
        this.name = name;
        this.parent = parent;
        this.depth = parent == null ? 0 : parent.depth + 1;
        this.request = request;
        this.token = token;
        this.tools = List.copyOf(tools);
        this.policy = policy;
        this.input = input;
        this.turn = turn;
        this.droppedTools = droppedTools;
        this.root = root;
        this.state = root ? AgentLifecycle.RUNNING : AgentLifecycle.CREATED;
    }

    String storyId() { return request == null ? null : request.storyId(); }

    boolean ended() { return state.terminal() || ending; }

    AgentInfo info() {
        return new AgentInfo(id, name, parent == null ? null : parent.id, storyId(), state,
            request == null ? "" : request.task(), waitingFor, lastOutput, depth,
            request == null ? SpawnMode.BACKGROUND : request.mode(), branch);
    }
}
