package org.github.lscoughlin.jeorje.tools.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.PeerMessenger;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.testkit.InMemoryContextStore;
import org.github.lscoughlin.jeorje.tools.registry.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class PeerToolsTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir Path dir;

    /** A messenger that records calls and knows two peers. */
    static final class Stub implements PeerMessenger {
        final List<String> messages = new ArrayList<>();
        final List<String> questions = new ArrayList<>();

        @Override
        public void message(String peerId, String agent, String text) {
            if (peerId.equals("gone")) throw new IllegalStateException("peer gone has no SSH address");
            messages.add(peerId + "/" + agent + ": " + text);
        }

        @Override
        public CompletableFuture<String> ask(String peerId, String agent, String question) {
            questions.add(peerId + "/" + agent + ": " + question);
            return peerId.equals("silent") ? CompletableFuture.failedFuture(new IllegalStateException("refused"))
                    : CompletableFuture.completedFuture("answer: " + question);
        }

        @Override
        public List<String> peerIds() {
            return List.of("p1", "p2");
        }
    }

    private ToolContext context() {
        return new ToolContext(dir, new AgentId("root"), CancellationToken.none(), new InMemoryContextStore(),
                Clock.systemUTC());
    }

    private ToolRegistry registry(Stub stub) {
        var settings = AgentSettings.defaults("s1", dir, 100_000);
        var runtime = SubagentRuntime.builder("s1", settings, m -> null).store(new InMemoryContextStore()).build();
        try {
            return ToolRegistry.builder().registerAll(AgentTools.install(runtime, "s1", stub)).build();
        } finally {
            runtime.close();
        }
    }

    private String call(ToolRegistry registry, String tool, String json) throws Exception {
        var result = registry.execute(new ToolUse("u", tool, JSON.readTree(json)), context());
        return (result.error() ? "ERROR: " : "") + result.output();
    }

    @Test
    void theOverloadAddsTheTwoToolsAndTheOldSignatureStaysTheSame() {
        var settings = AgentSettings.defaults("s1", dir, 100_000);
        var runtime = SubagentRuntime.builder("s1", settings, m -> null).store(new InMemoryContextStore()).build();
        try {
            var plain = ToolRegistry.builder().registerAll(AgentTools.install(runtime, "s1")).build();
            assertFalse(plain.names().contains("send_message"));
        } finally {
            runtime.close();
        }
        var withPeers = registry(new Stub());
        assertTrue(withPeers.names().containsAll(List.of("send_message", "ask_peer")), withPeers.names().toString());
        assertEquals("send_message", SendMessageTool.NAME);
        assertEquals("ask_peer", AskPeerTool.NAME);
    }

    @Test
    void sendMessageAndAskPeerReachThePeer() throws Exception {
        var stub = new Stub();
        var registry = registry(stub);
        assertTrue(call(registry, "send_message", "{\"peer\":\"p1\",\"agent\":\"main\",\"text\":\"hi\"}")
                .contains("Message sent"));
        assertEquals(List.of("p1/main: hi"), stub.messages);
        assertEquals("answer: why?", call(registry, "ask_peer", "{\"peer\":\"p2\",\"question\":\"why?\"}"));
        assertEquals(List.of("p2/null: why?"), stub.questions);
    }

    @Test
    void missingFieldsGiveAClearErrorWithTheKnownPeers() throws Exception {
        var registry = registry(new Stub());
        var noPeer = call(registry, "send_message", "{\"peer\":\" \",\"text\":\"hi\"}");
        assertTrue(noPeer.startsWith("ERROR:") && noPeer.contains("peer") && noPeer.contains("p1, p2"), noPeer);
        var noText = call(registry, "send_message", "{\"peer\":\"p1\",\"text\":\" \"}");
        assertTrue(noText.startsWith("ERROR:") && noText.contains("text"), noText);
        var noQuestion = call(registry, "ask_peer", "{\"peer\":\"p1\",\"question\":\"\"}");
        assertTrue(noQuestion.startsWith("ERROR:") && noQuestion.contains("question"), noQuestion);
        assertTrue(call(registry, "ask_peer", "{\"question\":\"x\"}").startsWith("ERROR:"));
    }

    @Test
    void anUnknownPeerListsTheKnownPeerIds() throws Exception {
        var registry = registry(new Stub());
        var sent = call(registry, "send_message", "{\"peer\":\"gone\",\"text\":\"hi\"}");
        assertTrue(sent.startsWith("ERROR:") && sent.contains("no SSH address") && sent.contains("p1, p2"), sent);
        var asked = call(registry, "ask_peer", "{\"peer\":\"silent\",\"question\":\"q\"}");
        assertTrue(asked.startsWith("ERROR:") && asked.contains("refused") && asked.contains("p1, p2"), asked);
    }
}
