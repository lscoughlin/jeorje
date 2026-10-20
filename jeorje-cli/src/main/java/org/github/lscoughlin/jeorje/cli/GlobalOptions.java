package org.github.lscoughlin.jeorje.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/** The options that every command accepts. They can stand before or after the command name. */
public final class GlobalOptions {

    @Option(names = "--dir", scope = ScopeType.INHERIT, description = "Working directory. Default: the current directory.")
    private Path dir;

    @Option(names = "--model", scope = ScopeType.INHERIT, description = "Model name for this run.")
    private String model;

    @Option(names = "--base-url", scope = ScopeType.INHERIT, description = "Base URL of the OpenAI-compatible API.")
    private String baseUrl;

    @Option(names = "--peer", scope = ScopeType.INHERIT, paramLabel = "host:port",
            description = "Swarm peer. Repeat the option for more peers.")
    private final List<String> peers = new ArrayList<>();

    @Option(names = "--swarm-token", scope = ScopeType.INHERIT, description = "Shared swarm token.")
    private String swarmToken;

    @Option(names = "--yes", scope = ScopeType.INHERIT, description = "Approve all permission prompts.")
    private boolean yes;

    @Option(names = "--yolo", scope = ScopeType.INHERIT, description = "Turn off all permission checks.")
    private boolean yolo;

    @Option(names = "--trust", scope = ScopeType.INHERIT, description = "Trust the project config in this directory.")
    private boolean trust;

    @Option(names = "--no-mcp", scope = ScopeType.INHERIT, description = "Do not start the MCP servers of the config.")
    private boolean noMcp;

    @Option(names = "--no-mouse", scope = ScopeType.INHERIT, description = "Turn off mouse support in the interface.")
    private boolean noMouse;

    @Option(names = "--config", scope = ScopeType.INHERIT, description = "Extra config file (.yaml, .yml, or .json).")
    private Path configFile;

    public Path dir() { return dir; }

    public String model() { return model; }

    public String baseUrl() { return baseUrl; }

    public List<String> peers() { return peers; }

    public String swarmToken() { return swarmToken; }

    public boolean yes() { return yes; }

    public boolean yolo() { return yolo; }

    public boolean trust() { return trust; }

    public boolean noMouse() { return noMouse; }

    public boolean noMcp() { return noMcp; }

    public Path configFile() { return configFile; }
}
