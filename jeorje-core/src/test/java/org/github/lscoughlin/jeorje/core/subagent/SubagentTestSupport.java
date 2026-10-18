package org.github.lscoughlin.jeorje.core.subagent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.github.lscoughlin.jeorje.core.agent.AgentInput;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.Hit;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test doubles for the subagent tests. The testkit module depends on the core module, so the core tests cannot use
 * it. These doubles behave like the testkit classes.
 */
final class SubagentTestSupport {
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** A valid summary. Its keywords are in the history of {@link #WORK_TEXT}. */
    static final String VALID = """
        Outcome: completed
        Done: Wrote the parser.
        Files: Parser.java
        Branch: none
        Decisions: Used a regex because it is short.
        Verification: ran mvn test, it passed.
        Open issues: none
        Keywords: Parser.java, regex, parser""";

    /** A final assistant text that holds the keywords of {@link #VALID}. */
    static final String WORK_TEXT = "I wrote Parser.java with a regex parser.";

    private SubagentTestSupport() {}

    /** A failure with an HTTP status. */
    static final class HttpFailure extends RuntimeException {
        private final int status;

        HttpFailure(int status) {
            super("HTTP " + status);
            this.status = status;
        }

        public int status() { return status; }
    }

    sealed interface Step {
        record Events(List<LlmEvent> events) implements Step {}
        record Fail(int status) implements Step {}
        /** Waits until the token is cancelled. */
        record Hang(CountDownLatch emitted) implements Step {}
    }

    static Step text(String text) {
        return new Step.Events(List.of(new LlmEvent.TextDelta(text), new LlmEvent.MessageStop("end_turn")));
    }

    static Step call(String id, String tool, String json) {
        return new Step.Events(List.of(new LlmEvent.ToolUseStart(id, tool),
            new LlmEvent.ToolUseInput(id, json), new LlmEvent.MessageStop("tool_use")));
    }

    static Step fail(int status) { return new Step.Fail(status); }

    static Step hang(CountDownLatch emitted) { return new Step.Hang(emitted); }

    /** Plays the steps in order and records the requests. */
    static final class Scripted implements LlmClient {
        final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
        private final List<Step> steps;
        private final AtomicInteger next = new AtomicInteger();

        Scripted(Step... steps) { this.steps = List.of(steps); }

        int calls() { return requests.size(); }

        /** The text of all messages of one request. */
        String requestText(int index) {
            var sb = new StringBuilder(requests.get(index).system());
            for (var m : requests.get(index).messages()) {
                for (var c : m.content()) sb.append('\n').append(c);
            }
            return sb.toString();
        }

        @Override
        public Flow.Publisher<LlmEvent> stream(LlmRequest request, CancellationToken cancel) {
            requests.add(request);
            var index = next.getAndIncrement();
            return subscriber -> {
                var step = index < steps.size() ? steps.get(index) : null;
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                        switch (step) {
                            case null -> subscriber.onError(new IllegalStateException("script exhausted"));
                            case Step.Fail f -> subscriber.onError(new HttpFailure(f.status()));
                            case Step.Events e -> {
                                for (var ev : e.events()) {
                                    if (cancel.isCancelled()) break;
                                    subscriber.onNext(ev);
                                }
                                subscriber.onComplete();
                            }
                            case Step.Hang h -> {
                                h.emitted().countDown();
                                var gone = new CountDownLatch(1);
                                cancel.onCancel(gone::countDown);
                                try {
                                    gone.await(20, TimeUnit.SECONDS);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                                subscriber.onComplete();
                            }
                        }
                    }

                    @Override
                    public void cancel() { }
                });
            };
        }
    }

    @FunctionalInterface
    interface Body {
        ToolResultData run(JsonNode input, ToolContext context) throws Exception;
    }

    static Tool tool(String name, boolean readOnly, Body body) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "Tool " + name, MAPPER.createObjectNode().put("type", "object"));
            }

            @Override
            public boolean readOnly() { return readOnly; }

            @Override
            public ToolResultData execute(JsonNode input, ToolContext context) throws Exception {
                return body.run(input, context);
            }
        };
    }

    static Tool simple(String name) {
        return tool(name, false, (in, ctx) -> ToolResultData.ok("ok " + name));
    }

    /** A store that keeps the chunks in a list. */
    static final class ListStore implements ContextStore {
        final List<Chunk> chunks = new CopyOnWriteArrayList<>();

        @Override public void append(List<Chunk> c) { chunks.addAll(c); }
        @Override public List<Hit> search(HistoryQuery query) { return List.of(); }
        @Override public java.util.Optional<Chunk> get(String chunkId) {
            return chunks.stream().filter(c -> c.chunkId().equals(chunkId)).findFirst();
        }
        @Override public List<Chunk> window(String chunkId, int before, int after) { return List.of(); }
        @Override public void close() { }
    }

    static void waitUntil(BooleanSupplier condition) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in 10 s");
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** A runtime with one root agent and scripted models by model slug. */
    static final class Rig implements AutoCloseable {
        final Map<String, LlmClient> models = new ConcurrentHashMap<>();
        final List<SubagentEvent> events = new CopyOnWriteArrayList<>();
        final List<AgentInput> rootInput = new CopyOnWriteArrayList<>();
        final ListStore store = new ListStore();
        final CancellationToken rootToken = new CancellationToken();
        final AgentId root = new AgentId("root");
        final SubagentRuntime runtime;

        Rig(Path dir, SubagentLimits limits, CompletionSteps steps, LlmClient compactor, List<Tool> rootTools,
            PermissionPolicy rootPolicy) {
            var settings = AgentSettings.defaults("s1", dir, 100_000).withSleeper(d -> { });
            var builder = SubagentRuntime.builder("s1", settings, model -> {
                    var client = models.get(model == null ? "default" : model);
                    if (client == null) throw new IllegalStateException("no scripted model " + model);
                    return client;
                })
                .store(store).events(events::add).limits(limits).compactor(compactor);
            if (steps != null) builder.steps(steps);
            runtime = builder.build();
            runtime.registerRoot(root, "root", rootToken, rootTools, rootPolicy, rootInput::add, () -> 7);
        }

        Rig(Path dir) {
            this(dir, SubagentLimits.defaults().withAskTimeout(Duration.ofSeconds(5)), null, null, List.of(),
                a -> PermissionPolicy.Decision.ALLOW);
        }

        Rig model(String slug, LlmClient client) {
            models.put(slug, client);
            return this;
        }

        AgentId spawn(String name, String model) {
            return runtime.spawn(root, SpawnRequest.of(name, "Fix the Parser.java bug in regex handling.")
                .withModel(model));
        }

        List<AgentLifecycle> states(AgentId id) {
            var out = new ArrayList<AgentLifecycle>();
            for (var e : events) {
                if (e instanceof SubagentEvent.StateChanged s && s.agent().equals(id)) out.add(s.to());
            }
            return out;
        }

        Completion await(AgentId id) throws InterruptedException {
            return runtime.await(id, CancellationToken.none());
        }

        @Override
        public void close() { runtime.close(); }
    }
}
