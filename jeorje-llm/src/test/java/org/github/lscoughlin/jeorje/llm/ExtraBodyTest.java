package org.github.lscoughlin.jeorje.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The fields of {@code llm.extraBody} go into the request body, and OpenRouter gets a safe default. */
class ExtraBodyTest {
    private static final WireMapper WIRE = new WireMapper(JsonMapper.builder().build());
    private static final LlmRequest REQUEST = new LlmRequest("sys", List.of(Message.user("hi")), List.of(), 100);

    @Test
    void theConfiguredFieldsAreInTheBody() {
        var body = WIRE.request("m", REQUEST, Map.of("provider", Map.of("order", List.of("DeepInfra"),
            "allow_fallbacks", false), "temperature", 0.2));
        assertEquals("DeepInfra", body.at("/provider/order/0").asString());
        assertFalse(body.at("/provider/allow_fallbacks").asBoolean(true));
        assertEquals(0.2, body.get("temperature").asDouble());
        assertEquals("m", body.get("model").asString());
    }

    @Test
    void aFieldThatJeorjeSetsItselfCannotBeReplaced() {
        var body = WIRE.request("m", REQUEST, Map.of("model", "other", "stream", false, "max_tokens", 1));
        assertEquals("m", body.get("model").asString());
        assertTrue(body.get("stream").asBoolean());
        assertEquals(100, body.get("max_tokens").asInt());
    }

    @Test
    void withNoExtraFieldsTheBodyIsTheSame() {
        assertEquals(WIRE.request("m", REQUEST), WIRE.request("m", REQUEST, Map.of()));
    }

    @Test
    void openRouterGetsRequireParametersUnlessTheConfigSetsAProvider() {
        var openRouter = OpenAiSettings.effectiveExtraBody("https://openrouter.ai/api/v1", Map.of());
        assertEquals(Map.of("provider", Map.of("require_parameters", true)), openRouter);
        var own = Map.<String, Object>of("provider", Map.of("order", List.of("X")));
        assertEquals(own, OpenAiSettings.effectiveExtraBody("https://openrouter.ai/api/v1", own));
        assertEquals(Map.of(), OpenAiSettings.effectiveExtraBody("https://api.example.test/v1", Map.of()));
        assertEquals(Map.of("a", 1), OpenAiSettings.effectiveExtraBody("not a url", Map.of("a", 1)));
    }

    @Test
    void theSettingsFromTheDefaultConfigCarryTheOpenRouterDefault() {
        var settings = OpenAiSettings.fromConfig(JeorjeConfig.defaults(), "m", null);
        assertEquals(Map.of("provider", Map.of("require_parameters", true)), settings.extraBody());
        assertFalse(settings.toString().contains("require_parameters"), "the text of the settings stays short");
    }
}
