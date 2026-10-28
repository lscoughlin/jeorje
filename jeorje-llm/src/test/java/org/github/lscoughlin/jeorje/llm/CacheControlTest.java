package org.github.lscoughlin.jeorje.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Cache breakpoints go to models whose name starts with {@code anthropic}, and the usage chunk reports the cache. */
class CacheControlTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final WireMapper WIRE = new WireMapper(MAPPER);

    private static final List<Message> MESSAGES = List.of(
        Message.user("first"),
        new Message(Role.ASSISTANT, List.of(new ToolUse("c1", "read", MAPPER.createObjectNode()))),
        new Message(Role.USER, List.of(new ToolResult("c1", "file text", false))),
        Message.user("[Current state]\ntodo"));

    private static LlmRequest request(int cachedPrefix) {
        return new LlmRequest("sys", MESSAGES, List.of(), 100, cachedPrefix);
    }

    private static boolean marked(tools.jackson.databind.JsonNode message) {
        return message.path("content").isArray() && message.at("/content/0/cache_control/type").asString().equals("ephemeral");
    }

    @Test
    void anAnthropicModelGetsABreakpointOnTheSystemPromptAndOnTheLastStableMessage() {
        var body = WIRE.request("anthropic/claude-sonnet-5-5", request(3));
        var messages = body.get("messages");
        assertEquals(5, messages.size(), "system, user, assistant, tool, trailing user");
        assertTrue(marked(messages.get(0)));
        assertEquals("sys", messages.at("/0/content/0/text").asString());
        assertFalse(marked(messages.get(1)));
        assertTrue(marked(messages.get(3)), "the tool message is the last wire message of the third message");
        assertEquals("file text", messages.at("/3/content/0/text").asString());
        assertFalse(marked(messages.get(4)), "the trailing state changes, so it has no breakpoint");
        assertEquals(2, body.toString().split("cache_control", -1).length - 1);
    }

    @Test
    void theModelNameDecides() {
        assertTrue(marked(WIRE.request("Anthropic/claude", request(3)).get("messages").get(0)));
        for (var model : List.of("deepseek/deepseek-chat", "openai/gpt-5", "claude-sonnet-5-5")) {
            assertFalse(WIRE.request(model, request(3)).toString().contains("cache_control"), model);
        }
    }

    @Test
    void aRequestThatDoesNotAskForTheCacheHasNoBreakpoints() {
        assertFalse(WIRE.request("anthropic/claude", request(-1)).toString().contains("cache_control"));
        var plain = new LlmRequest("sys", MESSAGES, List.of(), 100);
        assertFalse(WIRE.request("anthropic/claude", plain).toString().contains("cache_control"));
    }

    @Test
    void aStablePrefixOfZeroMessagesMarksOnlyTheSystemPrompt() {
        var body = WIRE.request("anthropic/claude", request(0));
        assertEquals(1, body.toString().split("cache_control", -1).length - 1);
        assertTrue(marked(body.at("/messages/0")));
    }

    @Test
    void aMessageWithNoTextKeepsItsShape() {
        var body = WIRE.request("anthropic/claude", request(2));
        var assistant = body.at("/messages/2");
        assertEquals("assistant", assistant.get("role").asString());
        assertTrue(assistant.get("content").isNull());
        assertFalse(assistant.toString().contains("cache_control"));
    }

    @Test
    void theBodyOfOtherModelsIsTheSameWithAndWithoutTheCacheAsk() {
        assertEquals(WIRE.request("deepseek/deepseek-chat", request(-1)), WIRE.request("deepseek/deepseek-chat", request(3)));
    }

    @Test
    void theUsageChunkReportsTheCacheNumbers() throws Exception {
        var openRouter = new StreamAssembler(MAPPER).accept("{\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":5,"
            + "\"prompt_tokens_details\":{\"cached_tokens\":800,\"cache_write_tokens\":150}}}");
        assertEquals(List.of(new LlmEvent.Usage(1000, 5, 800, 150)), openRouter);

        var deepSeek = new StreamAssembler(MAPPER).accept(
            "{\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":5,\"prompt_cache_hit_tokens\":640}}");
        assertEquals(List.of(new LlmEvent.Usage(1000, 5, 640, 0)), deepSeek);

        var none = new StreamAssembler(MAPPER).accept("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}");
        assertEquals(List.of(new LlmEvent.Usage(10, 2)), none);
    }

    @Test
    void textPartsStillMapWithoutTheCache() {
        var body = WIRE.request("anthropic/claude", new LlmRequest("", List.of(new Message(Role.USER, List.of(new Text("a")))),
            List.of(), 10, 1));
        assertTrue(marked(body.at("/messages/0")));
        assertEquals(1, body.get("messages").size(), "an empty system prompt adds no message");
    }
}
