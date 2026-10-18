package org.github.lscoughlin.jeorje.tools.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.function.Function;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;

/**
 * The {@code advisor} tool. It sends the working context of the calling agent to the advisor model,
 * with no tools, and returns the advice. Register it only when an advisor model is set.
 */
public final class AdvisorTool extends RecordTool<AdvisorTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "advisor";
    static final int MAX_OUTPUT_TOKENS = 4096;
    static final String DEFAULT_QUESTION = "Review the whole task. Say what I should do next, and what I may have missed.";

    /** The input of {@code advisor}. */
    public record Input(
            @Describe("What you want to know. With no question the advisor reviews the whole task.") String question) {}

    private final LlmClient client;
    private final Function<AgentId, AdvisorContext> contexts;

    /**
     * @param client the client of the advisor model
     * @param contexts gives the working context of an agent
     */
    public AdvisorTool(LlmClient client, Function<AgentId, AdvisorContext> contexts) {
        super(NAME, "Ask a second, stronger model to review your work. Call it before you start large "
                + "work, when you are stuck, and before you say that a task is done.", Input.class, true);
        this.client = client;
        this.contexts = contexts;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) throws Exception {
        var working = contexts.apply(context.agent());
        var question = input.question() == null || input.question().isBlank() ? DEFAULT_QUESTION : input.question();
        var messages = new ArrayList<Message>(working.messages());
        messages.add(Message.user("[Advisor request] " + question));
        var system = (working.system() == null ? "" : working.system() + "\n\n")
                + "You are the advisor of the agent above. You have no tools. Give short, concrete advice.";
        var request = new LlmRequest(system, messages, List.of(), MAX_OUTPUT_TOKENS);
        var advice = collect(client.stream(request, context.cancel()), context.cancel());
        if (advice.isBlank()) return ToolResultData.error("The advisor gave no answer.");
        return ToolResultData.ok(advice);
    }

    private static String collect(Flow.Publisher<LlmEvent> publisher, CancellationToken cancel) throws Exception {
        var done = new CompletableFuture<String>();
        var text = new StringBuilder();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) {
                cancel.onCancel(s::cancel);
                s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(LlmEvent event) {
                if (event instanceof LlmEvent.TextDelta delta) text.append(delta.text());
            }
            @Override public void onError(Throwable t) { done.completeExceptionally(t); }
            @Override public void onComplete() { done.complete(text.toString()); }
        });
        cancel.onCancel(() -> done.cancel(true));
        return done.get().strip();
    }
}
