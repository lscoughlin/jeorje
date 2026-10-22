package org.github.lscoughlin.jeorje.core.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the {@code .mcp.json} file of a directory into MCP server settings.
 *
 * <p>The file holds the object {@code mcpServers}. A bad entry gets one warning and Jeorje skips
 * it. The other entries stay. This class never throws for bad file content.
 */
public final class ProjectMcpFile {

    public static final String FILE_NAME = ".mcp.json";

    private static final Logger log = LoggerFactory.getLogger(ProjectMcpFile.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");

    private ProjectMcpFile() {
    }

    /** Reads {@code dir/.mcp.json}. A missing file gives an empty map. */
    public static Map<String, JeorjeConfig.McpServer> read(Path dir, Map<String, String> env, Consumer<String> warn) {
        var result = new LinkedHashMap<String, JeorjeConfig.McpServer>();
        var file = dir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return result;
        }
        JsonNode root;
        try {
            root = JSON.readTree(Files.readString(file));
        } catch (Exception e) {
            log.atDebug().setCause(e).log(() -> "Cannot read " + file);
            warn.accept("Ignored " + file + ": it is not valid JSON.");
            return result;
        }
        if (root == null || !root.isObject()) {
            warn.accept("Ignored " + file + ": the top level must be a JSON object.");
            return result;
        }
        var servers = root.get("mcpServers");
        if (servers == null || !servers.isObject()) {
            warn.accept("Ignored " + file + ": the key mcpServers must be a JSON object.");
            return result;
        }
        for (var entry : servers.properties()) {
            var name = entry.getKey();
            try {
                result.put(name, convert(name, entry.getValue(), dir, env));
            } catch (IllegalArgumentException e) {
                warn.accept("Skipped MCP server '" + name + "' in " + file + ": " + e.getMessage() + ".");
            } catch (RuntimeException e) {
                log.atDebug().setCause(e).log(() -> "Cannot convert MCP server " + name);
                warn.accept("Skipped MCP server '" + name + "' in " + file + ": the entry is not valid.");
            }
        }
        return result;
    }

    private static JeorjeConfig.McpServer convert(String name, JsonNode node, Path dir, Map<String, String> env) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("the name must use only a-z, 0-9, _ and -");
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("the entry must be a JSON object");
        }
        var command = text(node, "command");
        var url = text(node, "url");
        var type = text(node, "type");
        if (type == null) {
            type = command != null ? "stdio" : url != null ? "http" : null;
        }
        if (type == null) {
            throw new IllegalArgumentException("the entry has no type, command, or url");
        }
        var args = new ArrayList<String>();
        var argsNode = node.get("args");
        if (argsNode != null && argsNode.isArray()) {
            argsNode.forEach(a -> args.add(expand(a.asString(), env)));
        } else if (argsNode != null && !argsNode.isNull()) {
            throw new IllegalArgumentException("args must be a list");
        }
        String cwd = null;
        var cwdText = text(node, "cwd");
        if (cwdText != null) {
            var expanded = PathExpander.expand(expand(cwdText, env), Path.of(System.getProperty("user.home")), dir);
            cwd = dir.toAbsolutePath().resolve(expanded).normalize().toString();
        }
        var headers = strings(node.get("headers"), env, "headers");
        var envMap = strings(node.get("env"), env, "env");
        return switch (type) {
            case "stdio" -> {
                if (command == null || command.isBlank()) {
                    throw new IllegalArgumentException("a stdio server needs a command");
                }
                yield new JeorjeConfig.McpServer("stdio", expand(command, env), args, cwd, envMap, null, null,
                        headers, null, null);
            }
            case "http" -> {
                if (url == null || url.isBlank()) {
                    throw new IllegalArgumentException("an http server needs a url");
                }
                yield new JeorjeConfig.McpServer("http", null, args, cwd, envMap, null, expand(url, env), headers,
                        null, null);
            }
            default -> throw new IllegalArgumentException("the type '" + type + "' is not supported (use stdio or http)");
        };
    }

    private static String text(JsonNode node, String key) {
        var value = node.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Map<String, String> strings(JsonNode node, Map<String, String> env, String key) {
        var out = new LinkedHashMap<String, String>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException(key + " must be a JSON object");
        }
        for (var e : node.properties()) {
            out.put(e.getKey(), expand(e.getValue().asString(), env));
        }
        return out;
    }

    private static String expand(String value, Map<String, String> env) {
        return PLACEHOLDER.matcher(value).replaceAll(m -> Matcher.quoteReplacement(env.getOrDefault(m.group(1), "")));
    }
}
