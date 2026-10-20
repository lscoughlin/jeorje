package org.github.lscoughlin.jeorje.tools.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The behavior of the fake MCP server. Both fake transports use it. */
final class FakeMcpLogic {
    static final JsonMapper JSON = JsonMapper.builder().build();

    private FakeMcpLogic() {}

    /** Returns the response for a request, or null for a notification or a request that never gets an answer. */
    static ObjectNode handle(JsonNode request) {
        var method = request.path("method").asString("");
        if (!request.has("id")) return null;
        var params = request.path("params");
        var result = JSON.createObjectNode();
        switch (method) {
            case "initialize" -> {
                result.put("protocolVersion", "2025-06-18");
                result.putObject("capabilities").putObject("tools");
                result.putObject("serverInfo").put("name", "fake").put("version", "1");
            }
            case "tools/list" -> {
                var tools = result.putArray("tools");
                if (params.path("cursor").asString("").isEmpty()) {
                    tools.add(tool("echo", "Echoes text"));
                    tools.add(tool("fail", "Always fails"));
                    result.put("nextCursor", "p2");
                } else {
                    tools.add(tool("hang", "Never answers"));
                }
            }
            case "tools/call" -> {
                var tool = params.path("name").asString("");
                switch (tool) {
                    case "echo" -> text(result, "echo: " + params.path("arguments").path("text").asString(""), false);
                    case "fail" -> text(result, "boom", true);
                    case "hang" -> {
                        return null;
                    }
                    default -> {
                        return error(request, -32602, "Unknown tool: " + tool);
                    }
                }
            }
            default -> {
                return error(request, -32601, "Method not found");
            }
        }
        var response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        response.set("result", result);
        return response;
    }

    private static ObjectNode tool(String name, String description) {
        var tool = JSON.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        var schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        schema.putObject("properties").putObject("text").put("type", "string");
        return tool;
    }

    private static void text(ObjectNode result, String text, boolean isError) {
        result.putArray("content").addObject().put("type", "text").put("text", text);
        if (isError) result.put("isError", true);
    }

    private static ObjectNode error(JsonNode request, int code, String message) {
        var response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        response.putObject("error").put("code", code).put("message", message);
        return response;
    }
}
