package org.github.lscoughlin.jeorje.core.directory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.model.AgentId;

/** An {@link AgentDirectory} that keeps everything in memory. Tests use it. */
public final class InMemoryAgentDirectory implements AgentDirectory {
    public record Steering(AgentId agent, String text) {}

    private final Map<AgentId, AgentEntry> entries = new LinkedHashMap<>();
    private final Map<AgentId, List<String>> histories = new LinkedHashMap<>();
    private final List<Steering> steering = new ArrayList<>();

    public synchronized InMemoryAgentDirectory add(AgentEntry entry) {
        entries.put(entry.id(), entry);
        histories.putIfAbsent(entry.id(), new ArrayList<>());
        return this;
    }

    /** Adds a line to the history of an agent and sets it as the last output line. */
    public synchronized void appendOutput(AgentId agent, String line) {
        histories.computeIfAbsent(agent, k -> new ArrayList<>()).add(line);
        entries.computeIfPresent(agent, (k, e) -> e.withLastOutput(line));
    }

    public synchronized void setState(AgentId agent, String state) {
        entries.computeIfPresent(agent, (k, e) -> e.withState(state));
    }

    @Override
    public synchronized List<AgentEntry> agents() { return List.copyOf(entries.values()); }

    /** One harness, {@code local}, with the entries as agents and, by parent, as subagents. */
    @Override
    public synchronized List<RosterNode> tree() {
        var roots = new ArrayList<RosterNode>();
        for (var entry : entries.values()) {
            if (entry.parent() == null || !entries.containsKey(entry.parent())) {
                roots.add(node(RosterKind.AGENT, entry, new java.util.HashSet<>()));
            }
        }
        return List.of(new RosterNode(RosterKind.HARNESS, "harness:local", "local", "", roots));
    }

    private RosterNode node(RosterKind kind, AgentEntry entry, java.util.Set<AgentId> seen) {
        var children = new ArrayList<RosterNode>();
        if (seen.add(entry.id())) {
            for (var other : entries.values()) {
                if (entry.id().equals(other.parent())) children.add(node(RosterKind.SUBAGENT, other, seen));
            }
        }
        return new RosterNode(kind, entry.id().value(), entry.name(), entry.state(), children);
    }

    @Override
    public synchronized List<String> history(AgentId agent) {
        return List.copyOf(histories.getOrDefault(agent, List.of()));
    }

    @Override
    public synchronized void steer(AgentId agent, String text) {
        steering.add(new Steering(agent, text));
    }

    public synchronized List<Steering> steeringSent() { return List.copyOf(steering); }
}
