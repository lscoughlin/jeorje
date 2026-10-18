package org.github.lscoughlin.jeorje.core.subagent;

import java.nio.file.Path;
import java.util.List;

/**
 * The input of a spawn.
 *
 * @param name the short label that the UI shows
 * @param task the instruction for the subagent
 * @param tools the tool names, or null for the default (the tools of the parent without {@code spawn_agent})
 * @param type the name of a subagent definition, or null
 * @param model the model slug, or null for the default model
 * @param isolation {@code worktree} or {@code shared}, or null. The worktree story uses it.
 * @param mode the result mode
 * @param workdir an explicit working directory, or null
 * @param storyId the story id of a worker of {@code /implement}, or null
 * @param peer the peer that runs the task (an id, a name, or an address), or null for a local subagent
 * @param capability a capability filter such as {@code tools:bash}, or null. It picks a peer when {@code peer} is null.
 */
public record SpawnRequest(String name, String task, List<String> tools, String type, String model,
                           String isolation, SpawnMode mode, Path workdir, String storyId,
                           String peer, String capability) {
    public SpawnRequest {
        mode = mode == null ? SpawnMode.BACKGROUND : mode;
        tools = tools == null ? null : List.copyOf(tools);
        peer = peer == null || peer.isBlank() ? null : peer;
        capability = capability == null || capability.isBlank() ? null : capability;
    }

    /** A request for a local subagent. */
    public SpawnRequest(String name, String task, List<String> tools, String type, String model,
                        String isolation, SpawnMode mode, Path workdir, String storyId) {
        this(name, task, tools, type, model, isolation, mode, workdir, storyId, null, null);
    }

    /** True if a peer of the swarm runs the task. */
    public boolean remote() { return peer != null || capability != null; }

    public SpawnRequest withPeer(String next) {
        return new SpawnRequest(name, task, tools, type, model, isolation, mode, workdir, storyId, next, capability);
    }

    public SpawnRequest withCapability(String next) {
        return new SpawnRequest(name, task, tools, type, model, isolation, mode, workdir, storyId, peer, next);
    }

    /** A request with the default values and a background mode. */
    public static SpawnRequest of(String name, String task) {
        return new SpawnRequest(name, task, null, null, null, null, SpawnMode.BACKGROUND, null, null);
    }

    public SpawnRequest withMode(SpawnMode next) {
        return new SpawnRequest(name, task, tools, type, model, isolation, next, workdir, storyId, peer, capability);
    }

    public SpawnRequest withTools(List<String> next) {
        return new SpawnRequest(name, task, next, type, model, isolation, mode, workdir, storyId, peer, capability);
    }

    public SpawnRequest withType(String next) {
        return new SpawnRequest(name, task, tools, next, model, isolation, mode, workdir, storyId, peer, capability);
    }

    public SpawnRequest withModel(String next) {
        return new SpawnRequest(name, task, tools, type, next, isolation, mode, workdir, storyId, peer, capability);
    }

    public SpawnRequest withIsolation(String next) {
        return new SpawnRequest(name, task, tools, type, model, next, mode, workdir, storyId, peer, capability);
    }

    public SpawnRequest withWorkdir(Path next) {
        return new SpawnRequest(name, task, tools, type, model, isolation, mode, next, storyId, peer, capability);
    }

    public SpawnRequest withStoryId(String next) {
        return new SpawnRequest(name, task, tools, type, model, isolation, mode, workdir, next, peer, capability);
    }
}
