package org.github.lscoughlin.jeorje.tools.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** A fake MCP server for stdio. The tests start it as a child process. */
public final class FakeMcpServer {
    private FakeMcpServer() {}

    public static void main(String[] args) throws Exception {
        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            var response = FakeMcpLogic.handle(FakeMcpLogic.JSON.readTree(line));
            if (response != null) {
                System.out.println(FakeMcpLogic.JSON.writeValueAsString(response));
                System.out.flush();
            }
        }
    }
}
