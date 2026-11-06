package org.github.lscoughlin.jeorje.core.implement;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.agent.Agent;
import org.github.lscoughlin.jeorje.core.agent.AgentInput;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.agent.TranscriptSink;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The model loop of the orchestrator. It owns the {@link Agent} of the orchestrator, a queue of inputs, and one
 * dedicated thread that runs the turns one at a time.
 *
 * <p>Entry points that this class uses:
 * <ul>
 *   <li>{@code MainSession} and {@code SubagentRuntime.run} show how an {@link Agent} is built: the constructor
 *       {@code new Agent(id, settings, llm, tools, policy, store, transcript, events, clock)}.</li>
 *   <li>{@link Agent#runTurn(String, int)} runs one turn on the calling thread with a budget of model calls.</li>
 *   <li>{@link Agent#steer(String)} gives a text of the user to a running turn at its next step boundary.</li>
 *   <li>{@code SubagentRuntime.registerRoot} makes the root node. Its input callback calls {@link #offer}. The
 *       asker of {@code agent_ask} calls the callback on its own thread, so {@link #offer} only queues.</li>
 *   <li>{@link SubagentRuntime#answer} closes a question. The tool {@code agent_answer} calls it.</li>
 * </ul>
 *
 * <p>A question that arrives while a tool call of {@code ask_user} waits gets the fallback answer at once. A turn
 * that ends while its question is still open closes the question with the fallback answer.
 */
final class OrchestratorLoop implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OrchestratorLoop.class);
    private static final Pattern QUESTION = Pattern.compile("\\[question from [^\\n]*?, id (q\\d+)\\]");
    private static final String ASK_USER = "ask_user";

    private final AgentId id;
    private final SubagentRuntime runtime;
    private final Agent agent;
    private final int turnCalls;
    private final Consumer<AgentEvent> events;
    private final Map<String, String> questionTexts = new ConcurrentHashMap<>();
    private final BlockingQueue<AgentInput> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger askingUser = new AtomicInteger();
    private final AtomicInteger turns = new AtomicInteger();
    private Thread thread;
    private volatile boolean closed;

    /**
     * @param llm gives the model client. The loop asks for it at the first model call.
     * @param context gives the compact view of the stories. The agent adds it to each request.
     * @param turnCalls the largest number of model calls in one turn
     */
    OrchestratorLoop(AgentId id, SubagentRuntime runtime, AgentSettings settings, Supplier<LlmClient> llm,
                     List<Tool> tools, PermissionPolicy policy, ContextStore store, TranscriptSink transcript,
                     Consumer<AgentEvent> events, Clock clock, Supplier<String> context, int turnCalls,
                     boolean interactive) {
        this.id = id;
        this.runtime = runtime;
        this.turnCalls = Math.max(1, turnCalls);
        this.events = events == null ? e -> { } : events;
        var base = settings.instructions();
        var prompt = OrchestratorPrompt.system(interactive);
        var fixed = base.isEmpty() ? prompt : base + "\n\n" + prompt;
        var own = new AgentSettings(settings.sessionId(), settings.workingDirectory(), settings.contextWindowTokens(),
            settings.budgetPercent(), settings.maxOutputTokens(), settings.recentTurns(), settings.stepLimit(),
            settings.retry(), settings.systemPrompt(), fixed, settings.sleeper(), null,
            settings.maxProtectedTokens(), settings.recentMaxPercent(), settings.repeatGuard());
        var guarded = tools.stream().map(t -> ASK_USER.equals(t.definition().name()) ? (Tool) new Guard(t) : t).toList();
        this.agent = new Agent(id, own, lazy(llm), guarded, policy, store, transcript, this.events, clock);
        agent.withTrailing(() -> {
            var text = context.get();
            return text == null || text.isBlank() ? List.of() : List.of("Stories of the run:\n" + text);
        });
    }

    AgentId id() { return id; }

    /** The agent of the orchestrator. */
    Agent agent() { return agent; }

    /** The number of turns that the loop started. */
    int turns() { return turns.get(); }

    /** True while a call of {@code ask_user} of the orchestrator waits for the user. */
    boolean waitingForUser() { return askingUser.get() > 0; }

    /** Starts the thread. A second call does nothing. */
    synchronized void start() {
        if (thread != null || closed) return;
        thread = Thread.ofVirtual().name("subagent-" + id).start(this::run);
    }

    /**
     * Takes an input. It never runs a turn on the calling thread. A message and a question go to the queue. A
     * subagent result goes nowhere: the context of the next request shows its effect. A text of the user joins a
     * running turn at its next step boundary, or goes to the queue.
     */
    void offer(AgentInput input) {
        if (input instanceof AgentInput.PeerMessage || input instanceof AgentInput.UserInput) start();
        switch (input) {
            case AgentInput.PeerMessage message -> {
                var question = questionId(message);
                if (question != null) questionTexts.put(question, message.text());
                if (question != null && waitingForUser()) {
                    runtime.answer(id, question, OrchestratorPrompt.WAITING_FOR_USER);
                    return;
                }
                queue.add(message);
            }
            case AgentInput.UserInput user -> {
                if (!(agent.turnActive() && agent.steer(user.text()))) queue.add(user);
            }
            case AgentInput.SubagentResult ignored -> { }
            case AgentInput.Stop ignored -> { }
        }
    }

    /** The text of a question that came in, or null when the id is unknown. */
    String questionText(String questionId) { return questionTexts.get(questionId); }

    static String questionId(AgentInput input) {
        if (!(input instanceof AgentInput.PeerMessage message)) return null;
        var m = QUESTION.matcher(message.text());
        return m.find() ? m.group(1) : null;
    }

    private void run() {
        try {
            while (!closed) {
                var input = queue.take();
                if (closed) return;
                serve(input);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void serve(AgentInput input) {
        var question = questionId(input);
        if (question != null && !runtime.questionOpen(question)) {
            log.atDebug().log(() -> "The question " + question + " is closed. The orchestrator skips it.");
            return;
        }
        turns.incrementAndGet();
        try {
            agent.runTurn(input.text(), turnCalls);
        } catch (RuntimeException e) {
            log.atError().setCause(e).log(() -> "A turn of the orchestrator failed");
            events.accept(new AgentEvent.Error(id, "Internal error: "
                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
        } finally {
            // The check for an open question is in this one place.
            if (question != null) runtime.answer(id, question, OrchestratorPrompt.NO_ANSWER);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        agent.cancel();
        queue.add(new AgentInput.Stop());
        if (thread != null) thread.interrupt();
        // Questions that wait in the queue get an answer, so that no asker waits for the full time.
        for (var input : queue) {
            var question = questionId(input);
            if (question != null) runtime.answer(id, question, OrchestratorPrompt.NO_ANSWER);
        }
    }

    /** A model client that asks for the real client at the first call. */
    private static LlmClient lazy(Supplier<LlmClient> source) {
        return new LlmClient() {
            private volatile LlmClient client;

            @Override
            public Flow.Publisher<LlmEvent> stream(LlmRequest request, CancellationToken cancel) {
                var c = client;
                if (c == null) {
                    c = source.get();
                    client = c;
                }
                return c.stream(request, cancel);
            }
        };
    }

    /** Counts the open calls of {@code ask_user}. */
    private final class Guard implements Tool {
        private final Tool inner;

        Guard(Tool inner) { this.inner = inner; }

        @Override public ToolDefinition definition() { return inner.definition(); }

        @Override public boolean readOnly() { return inner.readOnly(); }

        @Override public java.util.Optional<String> check(JsonNode input) { return inner.check(input); }

        @Override
        public ToolResultData execute(JsonNode input, ToolContext context) throws Exception {
            askingUser.incrementAndGet();
            try {
                return inner.execute(input, context);
            } finally {
                askingUser.decrementAndGet();
            }
        }
    }
}
