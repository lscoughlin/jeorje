package org.github.lscoughlin.jeorje.testkit;

import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/** An {@link LlmClient} that plays a fixed list of responses and records each request. */
public final class ScriptedLlmClient implements LlmClient {

    /** The error that a failing response signals. */
    public static final class HttpFailure extends RuntimeException {
        private final int status;

        public HttpFailure(int status) {
            super("HTTP " + status);
            this.status = status;
        }

        public int status() { return status; }
    }

    /** One scripted response: a list of events, or a failure. */
    public record Response(List<LlmEvent> events, Integer failStatus) {
        public Response {
            events = List.copyOf(events);
        }

        public static Builder builder() { return new Builder(); }

        /** A response with the given text fragments and a stop reason of end_turn. */
        public static Response text(String... fragments) {
            return builder().text(fragments).stop("end_turn").build();
        }

        /** A response with one tool call and a stop reason of tool_use. */
        public static Response toolCall(String id, String tool, String json) {
            return builder().toolUse(id, tool, json).stop("tool_use").build();
        }

        /** A response that fails with an HTTP status. */
        public static Response failure(int status) {
            return new Response(List.of(), status);
        }
    }

    /** Builds a {@link Response}. */
    public static final class Builder {
        private final List<LlmEvent> events = new ArrayList<>();

        public Builder text(String... fragments) {
            for (var fragment : fragments) events.add(new LlmEvent.TextDelta(fragment));
            return this;
        }

        /** Adds a tool call. The JSON input streams in two fragments. */
        public Builder toolUse(String id, String tool, String json) {
            events.add(new LlmEvent.ToolUseStart(id, tool));
            var half = json.length() / 2;
            events.add(new LlmEvent.ToolUseInput(id, json.substring(0, half)));
            events.add(new LlmEvent.ToolUseInput(id, json.substring(half)));
            return this;
        }

        public Builder usage(int inputTokens, int outputTokens) {
            events.add(new LlmEvent.Usage(inputTokens, outputTokens));
            return this;
        }

        public Builder stop(String reason) {
            events.add(new LlmEvent.MessageStop(reason));
            return this;
        }

        public Response build() {
            var last = events.isEmpty() ? null : events.getLast();
            var all = new ArrayList<>(events);
            if (!(last instanceof LlmEvent.MessageStop)) all.add(new LlmEvent.MessageStop("end_turn"));
            return new Response(all, null);
        }
    }

    private final List<Response> script = new CopyOnWriteArrayList<>();
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger next = new AtomicInteger();

    public ScriptedLlmClient(List<Response> responses) {
        script.addAll(responses);
    }

    public ScriptedLlmClient(Response... responses) {
        this(List.of(responses));
    }

    /** Appends a response to the end of the script. */
    public ScriptedLlmClient then(Response response) {
        script.add(response);
        return this;
    }

    /** All requests, in order. */
    public List<LlmRequest> requests() { return List.copyOf(requests); }

    public int callCount() { return requests.size(); }

    @Override
    public Flow.Publisher<LlmEvent> stream(LlmRequest request, CancellationToken cancel) {
        requests.add(request);
        var index = next.getAndIncrement();
        return subscriber -> {
            var response = index < script.size() ? script.get(index) : null;
            subscriber.onSubscribe(new Flow.Subscription() {
                private int position;
                private boolean done;
                private long demand;
                private boolean draining;

                @Override
                public synchronized void request(long n) {
                    demand += n;
                    if (draining) return;
                    draining = true;
                    try {
                        drain();
                    } finally {
                        draining = false;
                    }
                }

                private void drain() {
                    while (!done) {
                        if (cancel.isCancelled()) {
                            done = true;
                            subscriber.onComplete();
                            return;
                        }
                        if (response == null) {
                            done = true;
                            subscriber.onError(new IllegalStateException(
                                "script exhausted at call " + (index + 1)));
                            return;
                        }
                        if (response.failStatus() != null) {
                            done = true;
                            subscriber.onError(new HttpFailure(response.failStatus()));
                            return;
                        }
                        if (position >= response.events().size()) {
                            done = true;
                            subscriber.onComplete();
                            return;
                        }
                        if (demand <= 0) return;
                        demand--;
                        subscriber.onNext(response.events().get(position++));
                    }
                }

                @Override
                public synchronized void cancel() { done = true; }
            });
        };
    }
}
