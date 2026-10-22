package org.github.lscoughlin.jeorje.cli.interactive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.jeorje.core.model.AgentId;

/**
 * Holds the live conversations of one process, in the order that they started. It also records the foreground
 * conversation. Only the input thread of the screen sets the foreground. An agent thread never sets it.
 */
final class SessionHub {
    private final Map<AgentId, MainSession> sessions = new LinkedHashMap<>();
    private final Map<AgentId, AgentId> parents = new LinkedHashMap<>();
    private final AtomicInteger forks = new AtomicInteger();
    private volatile AgentId foreground = MainSession.MAIN;

    /**
     * Adds a session.
     *
     * @param parent the id of the session that started this one, or null for the first session
     */
    synchronized void add(MainSession session, AgentId parent) {
        if (sessions.putIfAbsent(session.id(), session) != null) {
            throw new IllegalArgumentException("session exists: " + session.id());
        }
        parents.put(session.id(), parent);
    }

    /** The sessions in the order that they started. The list is a copy. */
    synchronized List<MainSession> sessions() {
        return new ArrayList<>(sessions.values());
    }

    /** The session with this id, or null. */
    synchronized MainSession get(AgentId id) {
        return sessions.get(id);
    }

    /** The id of the session that started this one, or null. */
    synchronized AgentId parent(AgentId id) {
        return parents.get(id);
    }

    AgentId foreground() { return foreground; }

    /** The foreground session, or null when no session exists. */
    MainSession foregroundSession() { return get(foreground); }

    /** Sets the foreground. Only the input thread of the screen calls this method. */
    void setForeground(AgentId id) { this.foreground = id; }

    /** The next id for a forked session: fork-1, fork-2, and so on. */
    AgentId nextForkId() {
        return new AgentId("fork-" + forks.incrementAndGet());
    }
}
