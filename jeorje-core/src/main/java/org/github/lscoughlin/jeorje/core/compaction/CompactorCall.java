package org.github.lscoughlin.jeorje.core.compaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import tools.jackson.databind.json.JsonMapper;

/** Sends one batch of chunks to the compactor model and parses the answer. */
final class CompactorCall {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int MAX_OUTPUT_TOKENS = 4096;
    private static final long TIMEOUT_SECONDS = 120;

    private final LlmClient client;

    CompactorCall(LlmClient client) { this.client = client; }

    /**
     * @param system the system prompt
     * @param feedback the reasons of an earlier failure by chunk id (empty on the first try)
     * @return the answers by chunk id
     * @throws RuntimeException when the call fails or the answer is not valid JSON
     */
    Map<String, CompactorAnswer> call(String system, List<Chunk> chunks, int level, Map<String, String> feedback) {
        var items = new ArrayList<Map<String, Object>>();
        for (var c : chunks) {
            var item = new java.util.LinkedHashMap<String, Object>();
            item.put("id", c.chunkId());
            item.put("kind", c.kind());
            if (c.toolName() != null) item.put("tool", c.toolName());
            item.put("text", c.text());
            if (feedback.containsKey(c.chunkId())) item.put("previousAnswerFailed", feedback.get(c.chunkId()));
            items.add(item);
        }
        var body = MAPPER.writeValueAsString(Map.of("level", level, "chunks", items));
        var request = new LlmRequest(system, List.of(Message.user(body)), List.of(), MAX_OUTPUT_TOKENS);
        return CompactorAnswer.parse(collect(client.stream(request, CancellationToken.none())));
    }

    private static String collect(Flow.Publisher<LlmEvent> publisher) {
        var result = new CompletableFuture<String>();
        var text = new StringBuilder();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }

            @Override public void onNext(LlmEvent e) {
                if (e instanceof LlmEvent.TextDelta d) text.append(d.text());
            }

            @Override public void onError(Throwable t) { result.completeExceptionally(t); }

            @Override public void onComplete() { result.complete(text.toString()); }
        });
        try {
            return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("compactor call failed", e);
        }
    }
}
