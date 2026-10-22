package org.github.lscoughlin.jeorje.cli.interactive;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.github.lscoughlin.jeorje.core.directory.AgentDirectory;
import org.github.lscoughlin.jeorje.core.directory.AgentEntry;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.subagent.AgentInfo;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.swarm.PeerRecord;
import org.github.lscoughlin.jeorje.swarm.PeerState;

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

    AgentRoster(AgentId main, Supplier<List<SubagentRuntime>> runtimes, Supplier<List<PeerRecord>> peers,
                Supplier<String> mainBranch, Supplier<String> mainState,
                Function<AgentId, List<String>> screenHistory, java.util.function.BiConsumer<AgentId, String> mainSteer) {
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
