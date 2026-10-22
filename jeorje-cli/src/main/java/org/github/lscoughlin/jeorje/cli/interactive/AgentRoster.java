package org.github.lscoughlin.jeorje.cli.interactive;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.github.lscoughlin.jeorje.core.directory.AgentDirectory;
import org.github.lscoughlin.jeorje.core.directory.AgentEntry;
import org.github.lscoughlin.jeorje.core.directory.RosterKind;
import org.github.lscoughlin.jeorje.core.directory.RosterNode;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.subagent.AgentInfo;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.swarm.PeerRecord;
import org.github.lscoughlin.jeorje.swarm.PeerState;
import org.github.lscoughlin.jeorje.swarm.SwarmHandlers;

/**
 * The agent directory of the interface. It lists the agent of the user, the subagents of the two runtimes (the
 * runtime of {@code spawn_agent} and the runtime of {@code /implement}), and the peers of the swarm.
 */
final class AgentRoster implements AgentDirectory {
    /** The prefix of the id of a remote peer in the list. */
    static final String PEER_PREFIX = "peer:";

    private final AgentId main;
    private final Supplier<List<SubagentRuntime>> runtimes;
    private final Supplier<List<PeerRecord>> peers;
    private final Supplier<String> mainBranch;
    private final Supplier<String> mainState;
    private final Function<AgentId, List<String>> screenHistory;
    private final java.util.function.BiConsumer<AgentId, String> mainSteer;
    private final Function<String, List<SwarmHandlers.PeerAgent>> remoteAgents;
    private final Supplier<String> harnessName;

    AgentRoster(AgentId main, Supplier<List<SubagentRuntime>> runtimes, Supplier<List<PeerRecord>> peers,
                Supplier<String> mainBranch, Supplier<String> mainState,
                Function<AgentId, List<String>> screenHistory, java.util.function.BiConsumer<AgentId, String> mainSteer) {
        this(main, runtimes, peers, mainBranch, mainState, screenHistory, mainSteer, peerId -> List.of(),
                () -> "this instance");
    }

    /**
     * @param remoteAgents gives the cached agents of a peer by peer id; it must not block
     * @param harnessName gives the display name of this instance
     */
    AgentRoster(AgentId main, Supplier<List<SubagentRuntime>> runtimes, Supplier<List<PeerRecord>> peers,
                Supplier<String> mainBranch, Supplier<String> mainState,
                Function<AgentId, List<String>> screenHistory, java.util.function.BiConsumer<AgentId, String> mainSteer,
                Function<String, List<SwarmHandlers.PeerAgent>> remoteAgents, Supplier<String> harnessName) {
        this.remoteAgents = remoteAgents;
        this.harnessName = harnessName;
        this.main = main;
        this.runtimes = runtimes;
        this.peers = peers;
        this.mainBranch = mainBranch;
        this.mainState = mainState;
        this.screenHistory = screenHistory;
        this.mainSteer = mainSteer;
    }

    @Override
    public List<AgentEntry> agents() {
        var out = new ArrayList<AgentEntry>();
        out.add(new AgentEntry(main, "main", null, mainState.get(), null, mainBranch.get(), ""));
        for (var runtime : runtimes.get()) {
            for (var info : runtime.status()) {
                if (info.parent() == null) continue;
                out.add(entry(info));
            }
        }
        for (var peer : peers.get()) {
            out.add(new AgentEntry(new AgentId(PEER_PREFIX + peer.peerId()), peer.name(), null,
                    peer.state().name(), peer.name(), null, peer.address()));
        }
        return out;
    }

    private AgentEntry entry(AgentInfo info) {
        // The parent of a worker is the orchestrator root. The user sees the agent of the user as the parent.
        var parent = info.parent();
        var rootOfRuntime = runtimes.get().stream().flatMap(r -> r.status().stream())
                .filter(i -> i.parent() == null).map(AgentInfo::id).toList();
        if (rootOfRuntime.contains(parent)) parent = main;
        var name = info.storyId() == null ? info.name() : info.name();
        return new AgentEntry(info.id(), name, parent, info.state().name(), null, info.branch(),
                info.lastOutput() == null ? "" : info.lastOutput());
    }

    /**
     * The tree: one harness for this instance and one for each live peer. A harness has its agents as children.
     * An agent has its subagents as children, and a subagent has its own subagents.
     */
    @Override
    public List<RosterNode> tree() {
        var out = new ArrayList<RosterNode>();
        var local = localOnly(PEER_PREFIX, agents());
        var byParent = new java.util.LinkedHashMap<String, List<AgentEntry>>();
        for (var entry : local) {
            var parent = entry.parent() == null ? "" : entry.parent().value();
            byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(entry);
        }
        var agents = new ArrayList<RosterNode>();
        for (var root : byParent.getOrDefault("", List.of())) {
            agents.add(localNode(RosterKind.AGENT, root, byParent, new java.util.HashSet<>()));
        }
        out.add(new RosterNode(RosterKind.HARNESS, "harness:local", harnessName.get(), "this instance", agents));
        for (var peer : peers.get()) {
            out.add(new RosterNode(RosterKind.HARNESS, PEER_PREFIX + peer.peerId(), peer.name(),
                    peer.state().name() + " " + peer.address(), remoteNodes(peer)));
        }
        return out;
    }

    private RosterNode localNode(RosterKind kind, AgentEntry entry, java.util.Map<String, List<AgentEntry>> byParent,
                                 java.util.Set<String> seen) {
        var children = new ArrayList<RosterNode>();
        if (seen.add(entry.id().value())) {
            for (var child : byParent.getOrDefault(entry.id().value(), List.of())) {
                children.add(localNode(RosterKind.SUBAGENT, child, byParent, seen));
            }
        }
        return new RosterNode(kind, entry.id().value(), entry.name(), entry.state(), children);
    }

    private List<RosterNode> remoteNodes(PeerRecord peer) {
        var agents = remoteAgents.apply(peer.peerId());
        var byParent = new java.util.LinkedHashMap<String, List<SwarmHandlers.PeerAgent>>();
        var known = new java.util.HashSet<String>();
        agents.forEach(a -> known.add(a.agentId()));
        for (var agent : agents) {
            // An agent whose parent is not in the list counts as a root agent.
            var parent = known.contains(agent.parentId()) ? agent.parentId() : "";
            byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(agent);
        }
        var out = new ArrayList<RosterNode>();
        for (var root : byParent.getOrDefault("", List.of())) {
            out.add(remoteNode(RosterKind.AGENT, peer.peerId(), root, byParent, new java.util.HashSet<>()));
        }
        return out;
    }

    private RosterNode remoteNode(RosterKind kind, String peerId, SwarmHandlers.PeerAgent agent,
                                  java.util.Map<String, List<SwarmHandlers.PeerAgent>> byParent,
                                  java.util.Set<String> seen) {
        var children = new ArrayList<RosterNode>();
        if (seen.add(agent.agentId())) {
            for (var child : byParent.getOrDefault(agent.agentId(), List.of())) {
                children.add(remoteNode(RosterKind.SUBAGENT, peerId, child, byParent, seen));
            }
        }
        return new RosterNode(kind, RemoteAgent.idOf(peerId, agent.agentId()), agent.name(), agent.state(), children);
    }

    @Override
    public List<String> history(AgentId agent) {
        return screenHistory.apply(agent);
    }

    @Override
    public void steer(AgentId agent, String text) {
        if (agent.equals(main)) {
            mainSteer.accept(agent, text);
            return;
        }
        if (agent.value().startsWith(PEER_PREFIX)) return;
        for (var runtime : runtimes.get()) {
            if (runtime.steer(agent, text)) return;
        }
    }

    /** Finds an agent by position (starting at 1), id, or name. */
    java.util.Optional<AgentEntry> find(String key) {
        var all = agents();
        if (key.matches("\\d+")) {
            var index = Integer.parseInt(key) - 1;
            if (index >= 0 && index < all.size()) return java.util.Optional.of(all.get(index));
        }
        return all.stream().filter(e -> e.id().value().equalsIgnoreCase(key) || e.name().equalsIgnoreCase(key))
                .findFirst();
    }

    /** The entries of the local agents: the list without the rows of remote peers. */
    static List<AgentEntry> localOnly(String peerPrefix, List<AgentEntry> all) {
        return all.stream().filter(e -> !e.id().value().startsWith(peerPrefix)).toList();
    }

    static List<PeerRecord> alive(List<PeerRecord> all) {
        return all.stream().filter(p -> p.state() == PeerState.ALIVE).toList();
    }
}
