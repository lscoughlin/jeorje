package org.github.lscoughlin.jeorje.llm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.model.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/** Calls the real OpenRouter API. Runs only when JEORJE_LIVE=1. The key comes from OPENROUTER_API_KEY. */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "JEORJE_LIVE", matches = "1")
class OpenAiCompatibleClientLiveTest {

    private List<LlmEvent> run(LlmRequest request) throws Exception {
        var key = System.getenv("OPENROUTER_API_KEY");
        assertNotNull(key, "OPENROUTER_API_KEY is not set");
        var settings = OpenAiSettings.of(OpenAiSettings.DEFAULT_BASE_URL, OpenAiSettings.DEFAULT_MODEL, key, null);
        var done = new CompletableFuture<List<LlmEvent>>();
        var events = new ArrayList<LlmEvent>();
        new OpenAiCompatibleClient(settings).stream(request, CancellationToken.none())
                .subscribe(new Flow.Subscriber<>() {
                    public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                    public void onNext(LlmEvent e) { events.add(e); }
                    public void onError(Throwable t) { done.completeExceptionally(t); }
                    public void onComplete() { done.complete(events); }
                });
        return done.get(120, TimeUnit.SECONDS);
    }

    @Test
    void streamsTextAndUsage() throws Exception {
        var events = run(new LlmRequest("Answer in one short sentence.",
                List.of(Message.user("Say hello.")), List.of(), 200));
        var text = new StringBuilder();
        events.forEach(e -> { if (e instanceof LlmEvent.TextDelta(var t)) text.append(t); });
        assertFalse(text.toString().isBlank());
        assertInstanceOf(LlmEvent.MessageStop.class, events.getLast());
        var usage = events.stream().filter(LlmEvent.Usage.class::isInstance).map(LlmEvent.Usage.class::cast)
                .findFirst().orElseThrow();
        assertTrue(usage.inputTokens() > 0);
        assertTrue(usage.outputTokens() > 0);
    }

    @Test
    void callsATool() throws Exception {
        var schema = JsonMapper.builder().build().readTree(
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}");
        var tool = new ToolDefinition("get_weather", "Gets the weather for a city.", schema);
        var events = run(new LlmRequest("Use the tool when the user asks about weather.",
                List.of(Message.user("What is the weather in Paris?")), List.of(tool), 500));
        var start = events.stream().filter(LlmEvent.ToolUseStart.class::isInstance)
                .map(LlmEvent.ToolUseStart.class::cast).findFirst().orElseThrow();
        assertEquals("get_weather", start.tool());
        var args = new StringBuilder();
        events.forEach(e -> { if (e instanceof LlmEvent.ToolUseInput(var id, var f)) args.append(f); });
        assertTrue(JsonMapper.builder().build().readTree(args.toString()).has("city"));
    }
}
