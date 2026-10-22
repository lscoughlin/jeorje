package org.github.lscoughlin.jeorje.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class SshMessengerTest {

    private static SwarmHandlers answering(List<String> received) {
        return new SwarmHandlers() {
            @Override
            public void onAgentMessage(String fromPeer, String toAgent, String text) {
                received.add(fromPeer + "/" + toAgent + ": " + text);
            }

            @Override
            public CompletionStage<String> onAgentAsk(String fromPeer, String toAgent, String question) {
                return question.startsWith("fail")
                        ? CompletableFuture.failedFuture(new IllegalStateException("cannot answer"))
                        : CompletableFuture.completedFuture("answer to " + question);
            }
        };
    }

    @Test
    void aMessageAndAnAskTravelOverSshAndTheAnswerComesBack() throws Exception {
        try (var cluster = new Cluster()) {
            var received = new CopyOnWriteArrayList<String>();
            var a = cluster.add("a", List.of());
            cluster.add("b", List.of("a"), "", answering(received));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));
            a.sendAgentMessage("b", "agent-1", "hello").get(5, TimeUnit.SECONDS);
            assertTrue(Cluster.await(() -> received.contains("a/agent-1: hello"), 5_000));
            assertEquals("answer to 2+2?", a.ask("b", "agent-1", "2+2?", Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void anErrorFrameFailsTheAskWithTheReason() {
        try (var cluster = new Cluster()) {
            var a = cluster.add("a", List.of());
            cluster.add("b", List.of("a"), "", answering(new CopyOnWriteArrayList<>()));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));
            var error = assertThrows(ExecutionException.class,
                    () -> a.ask("b", null, "fail please", Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS));
            assertEquals("cannot answer", error.getCause().getMessage());
        }
    }

    @Test
    void anAskToAnUnknownPeerOrAPeerWithoutAnSshAddressFailsWithoutThrowing() {
        try (var cluster = new Cluster()) {
            var a = cluster.add("a", List.of());
            assertTrue(a.ask("nobody", null, "hi?", Duration.ofSeconds(1)).isCompletedExceptionally());
            assertTrue(a.sendAgentMessage("nobody", null, "hi").isCompletedExceptionally());
            // A peer record without an SSH address.
            var bare = new SwarmNode(cluster.base.withWka(List.of("mem:a")),
                    PeerRecord.of("c", "mem:c", "c"), cluster.network.join("mem:c", "c"),
                    java.time.Clock.systemUTC(), new SwarmHandlers() {}, null);
            cluster.nodes.add(bare.start());
            assertTrue(Cluster.await(() -> a.members().containsKey("c"), 5_000));
            var error = assertThrows(ExecutionException.class,
                    () -> a.ask("c", null, "hi?", Duration.ofSeconds(1)).get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("no SSH address"), error.getCause().getMessage());
        }
    }

    @Test
    void aPeerThatNeverAnswersEndsWithATimeout() {
        try (var cluster = new Cluster()) {
            var a = cluster.add("a", List.of());
            cluster.add("b", List.of("a"), "", new SwarmHandlers() {
                @Override
                public CompletionStage<String> onAgentAsk(String fromPeer, String toAgent, String question) {
                    return new CompletableFuture<>();
                }
            });
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));
            var error = assertThrows(ExecutionException.class,
                    () -> a.ask("b", null, "hello?", Duration.ofMillis(200)).get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof TimeoutException);
        }
    }

    @Test
    void anAgentMessageOrQuestionOnTheTcpTransportIsIgnored() throws Exception {
        try (var cluster = new Cluster()) {
            var received = new CopyOnWriteArrayList<String>();
            var a = cluster.add("a", List.of());
            cluster.add("b", List.of("a"), "", answering(received));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));
            var send = SwarmNode.class.getDeclaredMethod("sendTo", String.class, String.class, Object.class);
            send.setAccessible(true);
            send.invoke(a, Cluster.address("b"), Messages.AGENT_MESSAGE,
                    new Messages.AgentPayload("text", "agent-1", null, null, null, "over tcp"));
            send.invoke(a, Cluster.address("b"), Messages.AGENT_ASK, new Messages.AskPayload("r1", null, "over tcp?"));
            // A message over SSH still works, so the earlier message had time to arrive.
            a.sendAgentMessage("b", "agent-1", "over ssh").get(5, TimeUnit.SECONDS);
            assertTrue(Cluster.await(() -> received.contains("a/agent-1: over ssh"), 5_000));
            assertEquals(List.of("a/agent-1: over ssh"), received);
        }
    }
}
