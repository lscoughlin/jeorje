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
