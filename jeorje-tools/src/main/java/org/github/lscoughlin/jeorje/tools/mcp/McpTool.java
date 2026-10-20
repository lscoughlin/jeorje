package org.github.lscoughlin.jeorje.tools.mcp;

import java.io.IOException;
import java.time.Duration;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.TimedTool;
import tools.jackson.databind.JsonNode;

/**
 * One tool of an MCP server. The name is {@code mcp__<server>__<tool>}. An MCP tool is never
 * read-only for the registry, so it never runs beside another tool.
 */
public final class McpTool implements TimedTool {
    /** Time on top of the call timeout, so that the client timeout fires before the registry timeout. */
    private static final Duration GRACE = Duration.ofSeconds(5);

    private final ToolDefinition definition;
    private final McpClient client;
    private final String remoteName;
    private final Duration callTimeout;

    public McpTool(String name, McpClient client, McpClient.RemoteTool remote, Duration callTimeout) {
        var description = remote.description().isBlank() ? "MCP tool " + remote.name() : remote.description();
        this.definition = new ToolDefinition(name, description, remote.inputSchema());
        this.client = client;
        this.remoteName = remote.name();
        this.callTimeout = callTimeout;
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Duration timeout() {
        return callTimeout.plus(GRACE);
    }

    @Override
    public ToolResultData execute(JsonNode input, ToolContext context) {
        try {
            var result = client.callTool(remoteName, input, callTimeout);
            var text = render(result.content());
            return result.isError() ? ToolResultData.error(text) : ToolResultData.ok(text);
        } catch (IOException e) {
            return ToolResultData.error(e.getMessage());
        }
    }

    /** Joins the content blocks. Binary blocks become one short line each. */
    static String render(JsonNode content) {
        var lines = new java.util.ArrayList<String>();
        for (var block : content) {
            var type = block.path("type").asString("");
            switch (type) {
                case "text" -> lines.add(block.path("text").asString(""));
                case "image", "audio" -> lines.add("[" + type + " omitted: " + block.path("mimeType").asString("unknown type")
                        + ", " + block.path("data").asString("").length() + " base64 characters]");
                case "resource" -> {
                    var resource = block.path("resource");
                    lines.add(resource.has("text") ? resource.path("text").asString("")
                            : "[resource omitted: " + resource.path("uri").asString("") + "]");
                }
                case "resource_link" -> lines.add("[resource link: " + block.path("uri").asString("") + "]");
                default -> lines.add("[content block omitted: " + type + "]");
            }
        }
        return lines.isEmpty() ? "(no output)" : String.join("\n", lines);
    }
}
