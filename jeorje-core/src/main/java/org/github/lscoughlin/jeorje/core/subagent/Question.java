package org.github.lscoughlin.jeorje.core.subagent;

import java.util.concurrent.CompletableFuture;
import org.github.lscoughlin.jeorje.core.model.AgentId;

/** One question between two agents. */
final class Question {
    final String id;
    final AgentId from;
    final AgentId to;
    final String text;
    final CompletableFuture<AskResult> reply = new CompletableFuture<>();

    Question(String id, AgentId from, AgentId to, String text) {
        this.id = id;
        this.from = from;
        this.to = to;
        this.text = text;
    }

    boolean open() { return !reply.isDone(); }
}
