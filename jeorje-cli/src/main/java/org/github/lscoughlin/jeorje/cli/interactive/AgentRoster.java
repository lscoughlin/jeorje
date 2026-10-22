package org.github.lscoughlin.jeorje.cli.interactive;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
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
 * The agent directory of the interface. It lists the agent of each live session, the subagents of the runtime of
 * each session, the workers of the runtime of {@code /implement}, and the peers of the swarm.
 */
final class AgentRoster implements AgentDirectory {
    /** The prefix of the id of a remote peer in the list. */
    static final String PEER_PREFIX = "peer:";

    /** The state of a session whose agent closed. */
    static final String ENDED = "ENDED";

    private final SessionHub hub;
    private final Supplier<List<SubagentRuntime>> extraRuntimes;
    private final Supplier<List<PeerRecord>> peers;
    private final Function<MainSession, String> branchOf;
    private final Function<AgentId, List<String>> screenHistory;
    private final BiConsumer<AgentId, String> sessionSteer;

    /**
     * @param extraRuntimes runtimes that no session owns, for example the runtime of {@code /implement}. Their
     *                      workers show the first session as their parent.
     * @param branchOf gives the branch of the working tree of a session
     * @param sessionSteer sends text to the main agent of a session
     */
    AgentRoster(SessionHub hub, Supplier<List<SubagentRuntime>> extraRuntimes, Supplier<List<PeerRecord>> peers,
                Function<MainSession, String> branchOf, Function<AgentId, List<String>> screenHistory,
                BiConsumer<AgentId, String> sessionSteer) {
        this.hub = hub;
        this.extraRuntimes = extraRuntimes;
        this.peers = peers;
        this.branchOf = branchOf;
        this.screenHistory = screenHistory;
        this.sessionSteer = sessionSteer;
    }

    @Override
    public List<AgentEntry> agents() {
        var out = new ArrayList<AgentEntry>();
        var sessions = hub.sessions();
        for (var session : sessions) {
            out.add(new AgentEntry(session.id(), session.name(), hub.parent(session.id()), stateOf(session), null,
                    branchOf.apply(session), ""));
            if (session.runtime() != null) addSubagents(out, session.runtime(), session.id());
        }
        var first = sessions.isEmpty() ? MainSession.MAIN : sessions.getFirst().id();
        for (var runtime : extraRuntimes.get()) addSubagents(out, runtime, first);
        for (var peer : peers.get()) {
            out.add(new AgentEntry(new AgentId(PEER_PREFIX + peer.peerId()), peer.name(), null,
                    peer.state().name(), peer.name(), null, peer.address()));
        }
        return out;
    }

    private static String stateOf(MainSession session) {
        if (session.ended()) return ENDED;
        return session.busy().get() ? "RUNNING" : "IDLE";
    }

    private static void addSubagents(List<AgentEntry> out, SubagentRuntime runtime, AgentId owner) {
        var status = runtime.status();
        var roots = status.stream().filter(i -> i.parent() == null).map(AgentInfo::id).toList();
        for (var info : status) {
            if (info.parent() == null) continue;
            // The parent of a worker is the root of its runtime. The user sees the session as the parent.
            var parent = roots.contains(info.parent()) ? owner : info.parent();
            out.add(new AgentEntry(info.id(), info.name(), parent, info.state().name(), null, info.branch(),
                    info.lastOutput() == null ? "" : info.lastOutput()));
        }
    }

    @Override
    public List<String> history(AgentId agent) {
        return screenHistory.apply(agent);
    }

    @Override
    public void steer(AgentId agent, String text) {
        if (hub.get(agent) != null) {
            sessionSteer.accept(agent, text);
            return;
        }
        if (agent.value().startsWith(PEER_PREFIX)) return;
        for (var session : hub.sessions()) {
            if (session.runtime() != null && session.runtime().steer(agent, text)) return;
        }
        for (var runtime : extraRuntimes.get()) {
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

    static List<PeerRecord> alive(List<PeerRecord> all) {
        return all.stream().filter(p -> p.state() == PeerState.ALIVE).toList();
    }
}
