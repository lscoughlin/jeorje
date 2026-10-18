package org.github.lscoughlin.jeorje.core.port;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** Filters for a history search. An empty set or an empty optional means no filter. */
public record Filters(Set<String> agentIds, OptionalInt fromTurn, OptionalInt toTurn,
                      Optional<Instant> from, Optional<Instant> to,
                      Set<String> kinds, Set<String> toolNames) {
    public Filters {
        agentIds = Set.copyOf(agentIds);
        kinds = Set.copyOf(kinds);
        toolNames = Set.copyOf(toolNames);
    }

    /** A filter that matches everything. */
    public static Filters none() {
        return new Filters(Set.of(), OptionalInt.empty(), OptionalInt.empty(),
            Optional.empty(), Optional.empty(), Set.of(), Set.of());
    }
}
