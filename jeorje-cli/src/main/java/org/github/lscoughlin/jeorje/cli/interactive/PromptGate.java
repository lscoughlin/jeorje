package org.github.lscoughlin.jeorje.cli.interactive;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.ui.screen.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks the user a question through the permission box of the screen and waits for the answer. Worker threads,
 * the trust prompt, and the plan approval use it. The agent of the user does not use it: that agent waits on its
 * own confirmation future.
 */
final class PromptGate {
    private static final Logger log = LoggerFactory.getLogger(PromptGate.class);

    private final Consumer<AgentEvent> post;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(AgentId agent, CompletableFuture<Decision> future) {}

    PromptGate(Consumer<AgentEvent> post) {
        this.post = post;
    }

    private static String key(AgentId agent, String toolUseId) {
        return agent.value() + "/" + toolUseId;
    }

    /** Shows the prompt and blocks until the user answers. A stop or a failure gives {@code DENY}. */
    Decision ask(AgentId agent, ToolUse use) {
        var future = new CompletableFuture<Decision>();
        var key = key(agent, use.id());
        pending.put(key, new Pending(agent, future));
        try {
            post.accept(new AgentEvent.ConfirmationRequested(agent, use));
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Decision.DENY;
        } catch (ExecutionException e) {
            log.atDebug().log(() -> "prompt failed: " + e.getCause());
            return Decision.DENY;
        } finally {
            pending.remove(key);
        }
    }

    /** Gives the answer of the user to a waiting caller. Returns false if nobody waits for this prompt. */
    boolean complete(AgentId agent, ToolUse use, Decision decision) {
        var found = pending.get(key(agent, use.id()));
        if (found == null) return false;
        found.future().complete(decision);
        return true;
    }

    /** Denies every waiting prompt of an agent. */
    void denyAgent(AgentId agent) {
        pending.values().stream().filter(p -> p.agent().equals(agent)).forEach(p -> p.future().complete(Decision.DENY));
    }

    /** Denies every waiting prompt. */
    void denyAll() {
        pending.values().forEach(p -> p.future().complete(Decision.DENY));
    }
}
