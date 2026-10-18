package org.github.lscoughlin.jeorje.cli;

import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.subagent.Completion;
import org.github.lscoughlin.jeorje.core.subagent.SpawnException;
import org.github.lscoughlin.jeorje.core.subagent.SpawnMode;
import org.github.lscoughlin.jeorje.core.subagent.SpawnRequest;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code spawn_agent} tool with the swarm fields. It adds the parameters {@code peer} and {@code capability}
 * to the tool of the tools module. A call without these fields goes to that tool. A call with one of them starts
 * the task on a peer, and the runtime shows the remote subagent as a local subagent.
 */
public final class PeerSpawnTool implements Tool {
    private final Tool local;
    private final SubagentRuntime runtime;

    public PeerSpawnTool(Tool local, SubagentRuntime runtime) {
        this.local = local;
        this.runtime = runtime;
    }

    /** Replaces the {@code spawn_agent} tool in a tool list. Other tools stay. */
    public static List<Tool> wrap(List<Tool> tools, SubagentRuntime runtime) {
        var out = new ArrayList<Tool>();
        for (var tool : tools) {
            out.add(tool.definition().name().equals(SubagentRuntime.SPAWN_TOOL) ? new PeerSpawnTool(tool, runtime)
                    : tool);
        }
        return out;
    }

    @Override
    public ToolDefinition definition() {
        var base = local.definition();
        var schema = base.inputSchema();
        if (schema instanceof ObjectNode root && root.get("properties") instanceof ObjectNode props) {
            props.set("peer", props.objectNode().put("type", "string").put("description",
                    "Run the task on this peer of the swarm (id, name, or address). The peer uses its own workspace "
                            + "and permissions."));
            props.set("capability", props.objectNode().put("type", "string").put("description",
                    "Run the task on any live peer with this capability, for example tools:bash."));
        }
        return new ToolDefinition(base.name(), base.description()
                + " With peer or capability, a peer of the swarm runs the task.", schema);
    }

    @Override
    public boolean readOnly() { return local.readOnly(); }

    @Override
    public ToolResultData execute(JsonNode input, ToolContext context) throws Exception {
        var peer = text(input, "peer");
        var capability = text(input, "capability");
        var copy = input == null || !input.isObject() ? input : ((ObjectNode) input.deepCopy())
                .without("peer").without("capability");
        if (peer == null && capability == null) return local.execute(copy, context);
        var name = text(input, "name");
        var task = text(input, "task");
        if (name == null || task == null) return ToolResultData.error("spawn_agent needs name and task.");
        SpawnMode mode;
        try {
            mode = SpawnMode.parse(text(input, "mode"));
        } catch (SpawnException e) {
            return ToolResultData.error(e.getMessage());
        }
        List<String> tools = null;
        if (input.get("tools") != null && input.get("tools").isArray()) {
            tools = new ArrayList<>();
            for (var t : input.get("tools")) tools.add(t.asString());
        }
        var request = new SpawnRequest(name, task, tools, null, null, null, mode, null, null, peer, capability);
        org.github.lscoughlin.jeorje.core.model.AgentId id;
        try {
            id = runtime.spawn(context.agent(), request);
        } catch (SpawnException e) {
            return ToolResultData.error("spawn_agent failed: " + e.getMessage());
        }
        if (mode == SpawnMode.BACKGROUND) {
            return ToolResultData.ok("Started remote subagent " + name + " with agent id " + id
                    + ". Its completion summary comes later as an input event.");
        }
        var completion = runtime.await(id, context.cancel());
        var textOut = "Remote subagent " + completion.name() + " (" + completion.agent() + ") ended with the outcome "
                + completion.outcome().name().toLowerCase(java.util.Locale.ROOT) + ".\n" + completion.summary();
        return completion.outcome() == Completion.Outcome.COMPLETED ? ToolResultData.ok(textOut)
                : ToolResultData.error(textOut);
    }

    private static String text(JsonNode input, String field) {
        if (input == null || input.get(field) == null || input.get(field).isNull()) return null;
        var value = input.get(field).asString();
        return value == null || value.isBlank() ? null : value;
    }
}
