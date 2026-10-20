package org.github.lscoughlin.jeorje.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;

/** A small MCP server for stdio with one tool, echo. The wiring tests start it as a child process. */
public final class FakeMcpEcho {
    private FakeMcpEcho() {}

    public static void main(String[] args) throws Exception {
        var json = JsonMapper.builder().build();
        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            var request = json.readTree(line);
            if (!request.has("id")) continue;
            var response = json.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            var result = response.putObject("result");
            switch (request.path("method").asString("")) {
                case "initialize" -> {
                    result.put("protocolVersion", "2025-06-18");
                    result.putObject("capabilities").putObject("tools");
                    result.putObject("serverInfo").put("name", "echo");
                }
                case "tools/list" -> {
                    var tool = result.putArray("tools").addObject();
                    tool.put("name", "echo");
                    tool.put("description", "Echoes text");
                    var schema = tool.putObject("inputSchema");
                    schema.put("type", "object");
                    schema.putObject("properties").putObject("text").put("type", "string");
                }
                default -> result.putArray("content").addObject().put("type", "text")
                        .put("text", "echo: " + request.path("params").path("arguments").path("text").asString(""));
            }
            System.out.println(json.writeValueAsString(response));
            System.out.flush();
        }
    }
}
