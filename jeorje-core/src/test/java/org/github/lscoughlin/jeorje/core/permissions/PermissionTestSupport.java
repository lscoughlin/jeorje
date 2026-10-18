package org.github.lscoughlin.jeorje.core.permissions;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import tools.jackson.databind.json.JsonMapper;

final class PermissionTestSupport {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PermissionTestSupport() {
    }

    static ToolUse use(String tool, Map<String, ?> input) {
        return new ToolUse("id-1", tool, JSON.valueToTree(input));
    }

    static ToolUse bash(String command) {
        return use("bash", Map.of("command", command));
    }

    static ToolUse path(String tool, String path) {
        return use(tool, Map.of("path", path));
    }

    static ToolUse fetch(String url) {
        return use("webfetch", Map.of("url", url));
    }

    /** A resolver with fixed answers. */
    static NetworkPolicy.Resolver resolver(Map<String, String> table) {
        return host -> {
            var literal = host.matches("[0-9.]+") || host.contains(":");
            var ip = literal ? host : table.getOrDefault(host, "93.184.216.34");
            return List.of(InetAddress.getByName(ip));
        };
    }
}
