package org.github.lscoughlin.jeorje.core.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MessageSerializationTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void contentRoundTrips() throws Exception {
        var input = mapper.readTree("{\"path\":\"a.txt\",\"n\":[1,2],\"o\":{\"x\":true}}");
        List<Content> all = List.of(new Text("hi"), new ToolUse("t1", "read", input),
            new ToolResult("t1", "out", true));
        for (var c : all) {
            var json = mapper.writeValueAsString(c);
            assertEquals(c, mapper.readValue(json, Content.class));
        }
        assertTrue(mapper.writeValueAsString(all.get(1)).contains("\"type\":\"tool_use\""));
    }

    @Test
    void messageRoundTrips() throws Exception {
        var input = mapper.readTree("{\"cmd\":\"ls\"}");
        var m = new Message(Role.ASSISTANT, List.of(new Text("run"), new ToolUse("t2", "bash", input)));
        var back = mapper.readValue(mapper.writeValueAsString(m), Message.class);
        assertEquals(m, back);
        assertEquals("ls", ((ToolUse) back.content().get(1)).input().get("cmd").asString());
    }

    @Test
    void messageCopiesListDefensively() {
        var list = new ArrayList<Content>(List.of(new Text("a")));
        var m = new Message(Role.USER, list);
        list.add(new Text("b"));
        assertEquals(1, m.content().size());
        assertThrows(UnsupportedOperationException.class, () -> m.content().add(new Text("c")));
    }

    @Test
    void toolUseInputIsCopied() throws Exception {
        var node = (tools.jackson.databind.node.ObjectNode) mapper.readTree("{\"a\":1}");
        var use = new ToolUse("i", "t", node);
        node.put("a", 2);
        assertEquals(1, use.input().get("a").asInt());
    }

    @Test
    void turnIdAndCopy() {
        var msgs = new ArrayList<Message>(List.of(Message.user("x")));
        var turn = new Turn(new AgentId("a1"), 3, msgs);
        msgs.clear();
        assertEquals("a1:3", turn.id());
        assertEquals(1, turn.messages().size());
    }

    @Test
    void cancellationTokenRunsCallbacksOnce() {
        var token = new CancellationToken();
        var count = new int[1];
        token.onCancel(() -> count[0]++);
        token.cancel();
        token.cancel();
        token.onCancel(() -> count[0]++);
        assertTrue(token.isCancelled());
        assertEquals(2, count[0]);
        assertThrows(java.util.concurrent.CancellationException.class, token::throwIfCancelled);
    }
}
