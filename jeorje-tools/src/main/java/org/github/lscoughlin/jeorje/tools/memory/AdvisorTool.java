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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code advisor} tool. It sends the working context of the calling agent to the advisor model,
 * with no tools, and returns the advice. Register it only when an advisor model is set.
 */
public final class AdvisorTool extends RecordTool<AdvisorTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "advisor";
    static final int MAX_OUTPUT_TOKENS = 4096;
    /** The largest output limit of the retry after an answer that the output limit cut off. */
    static final int MAX_RETRY_OUTPUT_TOKENS = 16384;
    /** The error text for an answer that the output limit cut off. */
    static final String LENGTH_ERROR = "The model used its output limit before it answered.";
    static final String EMPTY_ERROR = "The advisor gave no answer.";
    private static final Logger log = LoggerFactory.getLogger(AdvisorTool.class);

    /**
     * The result of one advice request.
     *
     * @param advice the advice. It is empty when the advisor gave no answer.
     * @param stopReason the stop reason of the last model call
     */
    public record Advice(String advice, String stopReason) {
        /** The error text for an empty advice, or null when the advice has text. */
        public String emptyReason() {
            if (!advice.isBlank()) return null;
            return LlmEvent.LENGTH.equals(stopReason) ? LENGTH_ERROR : EMPTY_ERROR;
        }
    }
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
        var answer = adviseWithReason(context.agent(), input.question(), context.cancel());
        var problem = answer.emptyReason();
        if (problem != null) return ToolResultData.error(problem);
        return ToolResultData.ok(answer.advice());
    }

    /**
     * Asks the advisor about the work of an agent. The {@code advisor} tool and the help request of the turn
     * watch of a subagent both use this method.
     *
     * @return the advice. It is empty when the advisor gave no answer.
     */
    public String advise(AgentId agent, String question, CancellationToken cancel) throws Exception {
        return adviseWithReason(agent, question, cancel).advice();
    }

    /**
     * Like {@link #advise}, and it also gives the stop reason. When the first answer is empty because the model
     * used its output limit, the method asks once more with the double output limit (at most 16384 tokens).
     */
    public Advice adviseWithReason(AgentId agent, String question, CancellationToken cancel) throws Exception {
        var working = contexts.apply(agent);
        var text = question == null || question.isBlank() ? DEFAULT_QUESTION : question;
        var messages = new ArrayList<Message>(working.messages());
        messages.add(Message.user("[Advisor request] " + text));
        var system = (working.system() == null ? "" : working.system() + "\n\n")
                + "You are the advisor of the agent above. You have no tools. Give short, concrete advice.";
        var first = collect(client.stream(new LlmRequest(system, messages, List.of(), MAX_OUTPUT_TOKENS), cancel),
                cancel);
        if (!first.advice().isBlank() || !LlmEvent.LENGTH.equals(first.stopReason())) return first;
        var larger = Math.min(MAX_OUTPUT_TOKENS * 2, MAX_RETRY_OUTPUT_TOKENS);
        log.atWarn().log(() -> "The advisor used its output limit of " + MAX_OUTPUT_TOKENS
                + " tokens and gave no text. It asks again with " + larger + " tokens.");
        return collect(client.stream(new LlmRequest(system, messages, List.of(), larger), cancel), cancel);
    }

    private static Advice collect(Flow.Publisher<LlmEvent> publisher, CancellationToken cancel) throws Exception {
        var done = new CompletableFuture<Advice>();
        var text = new StringBuilder();
        var stop = new String[1];
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) {
                cancel.onCancel(s::cancel);
                s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(LlmEvent event) {
                switch (event) {
                    case LlmEvent.TextDelta delta -> text.append(delta.text());
                    case LlmEvent.MessageStop end -> stop[0] = end.stopReason();
                    default -> { }
                }
            }
            @Override public void onError(Throwable t) { done.completeExceptionally(t); }
            @Override public void onComplete() { done.complete(new Advice(text.toString().strip(), stop[0])); }
        });
        cancel.onCancel(() -> done.cancel(true));
        return done.get();
    }
}
