package org.github.lscoughlin.jeorje.core.agent;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.model.Turn;
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
 * Test doubles for the agent tests. The testkit module depends on the core module, so the core tests
 * cannot use it. These doubles have the same behavior as the testkit classes that the tests need.
 */
final class AgentTestSupport {
    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);

    private AgentTestSupport() {}

    /** An HTTP-like failure. The retry policy reads {@code status()}. */
    public static final class HttpFailure extends RuntimeException {
        private final int status;
        private final Duration retryAfter;

        public HttpFailure(int status, Duration retryAfter) {
            super("HTTP " + status);
            this.status = status;
            this.retryAfter = retryAfter;
        }

        public int status() { return status; }

        public Duration retryAfter() { return retryAfter; }
    }

    /** One scripted response. */
    sealed interface Step {
        record Events(List<LlmEvent> events) implements Step {}
        record Fail(int status, Duration retryAfter) implements Step {}
        /** Sends the events, then waits until the token is cancelled, then completes. */
        record HangAfter(List<LlmEvent> events, CountDownLatch emitted) implements Step {}
    }

    static Step text(String... parts) {
        var list = new ArrayList<LlmEvent>();
        for (var p : parts) list.add(new LlmEvent.TextDelta(p));
        list.add(new LlmEvent.MessageStop("end_turn"));
        return new Step.Events(list);
    }

    static Step calls(String... idToolJson) {
        var list = new ArrayList<LlmEvent>();
        for (var i = 0; i < idToolJson.length; i += 3) {
            list.add(new LlmEvent.ToolUseStart(idToolJson[i], idToolJson[i + 1]));
            var json = idToolJson[i + 2];
            list.add(new LlmEvent.ToolUseInput(idToolJson[i], json.substring(0, json.length() / 2)));
            list.add(new LlmEvent.ToolUseInput(idToolJson[i], json.substring(json.length() / 2)));
        }
        list.add(new LlmEvent.MessageStop("tool_use"));
        return new Step.Events(list);
    }

    static Step withUsage(Step step, int in, int out) {
        var events = new ArrayList<>(((Step.Events) step).events());
        events.add(events.size() - 1, new LlmEvent.Usage(in, out));
        return new Step.Events(events);
    }

    static Step fail(int status) { return new Step.Fail(status, null); }

    /** Plays the steps in order and records the requests. */
    static final class Scripted implements LlmClient {
        final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
        private final List<Step> steps;
        private final AtomicInteger next = new AtomicInteger();

        Scripted(Step... steps) { this.steps = List.of(steps); }

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
                            case Step.Fail f -> subscriber.onError(new HttpFailure(f.status(), f.retryAfter()));
                            case Step.Events e -> {
                                for (var ev : e.events()) {
                                    if (cancel.isCancelled()) break;
                                    subscriber.onNext(ev);
                                }
                                subscriber.onComplete();
                            }
                            case Step.HangAfter h -> {
                                for (var ev : h.events()) subscriber.onNext(ev);
                                h.emitted().countDown();
                                var gone = new CountDownLatch(1);
                                cancel.onCancel(gone::countDown);
                                try {
                                    gone.await(10, TimeUnit.SECONDS);
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

    /** A tool with a lambda body. */
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

    @FunctionalInterface
    interface Body {
        ToolResultData run(JsonNode input, ToolContext context) throws Exception;
    }

    static PermissionPolicy policy(PermissionPolicy.Decision decision) {
        return action -> decision;
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

    /** Everything an agent test needs. */
    static final class Rig {
        final List<AgentEvent> events = new CopyOnWriteArrayList<>();
        final ListStore store = new ListStore();
        final List<Turn> transcript = new CopyOnWriteArrayList<>();
        final List<Duration> sleeps = new CopyOnWriteArrayList<>();
        final Agent agent;

        Rig(Scripted llm, PermissionPolicy policy, AgentSettings settings, Tool... tools) {
            var withSleep = settings.withSleeper(sleeps::add);
            agent = new Agent(new org.github.lscoughlin.jeorje.core.model.AgentId("a1"), withSleep, llm,
                List.of(tools), policy, store, (turn, ts) -> transcript.add(turn), events::add, CLOCK);
        }

        static AgentSettings settings(Path dir) {
            return AgentSettings.defaults("s1", dir, 100_000);
        }

        <T extends AgentEvent> List<T> events(Class<T> type) {
            return events.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    static ToolUse use(String id, String tool) {
        return new ToolUse(id, tool, MAPPER.createObjectNode());
    }
}
