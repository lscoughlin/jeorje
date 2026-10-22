package org.github.lscoughlin.jeorje.cli.interactive;

import java.util.Optional;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.subagent.AskResult;
import org.github.lscoughlin.jeorje.core.subagent.ExternalDelivery;

/**
 * Routes {@code agent_message} and {@code agent_ask} between the root agents of the live sessions. A runtime calls
 * this class when it cannot find the target in its own agents.
 *
 * <p>The runtime of the target session answers a question through its own question table. So the sender must be
 * known there. This class registers the sender in the runtime of the target, once, as an external sender. Agents
 * of the target session cannot find that node by name. Their messages to the sender come back to this class.
 */
final class SessionDirectory implements ExternalDelivery {
    private final SessionHub hub;

    SessionDirectory(SessionHub hub) {
        this.hub = hub;
    }

    @Override
    public Optional<AskResult> deliver(AgentId from, String fromName, AgentId to, String text, boolean question,
                                       CancellationToken cancel) {
        var target = find(to.value());
        var sender = hub.get(from);
        if (target == null || sender == null || target == sender) return Optional.empty();
        // The target is copied out of the hub. No lock of the hub is held while the question waits.
        var runtime = target.runtime();
        if (target.ended() || runtime == null) return Optional.of(AskResult.ended(null));
        runtime.registerExternalSender(from, fromName);
        var id = target.id().value();
        return Optional.of(question ? runtime.ask(from, fromName, id, text, cancel)
                : runtime.message(from, fromName, id, text));
    }

    /** Finds a session by id or by name. */
    private MainSession find(String key) {
        for (var session : hub.sessions()) {
            if (session.id().value().equalsIgnoreCase(key) || session.name().equalsIgnoreCase(key)) return session;
        }
        return null;
    }
}
