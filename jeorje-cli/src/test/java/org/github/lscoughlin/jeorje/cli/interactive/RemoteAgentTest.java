package org.github.lscoughlin.jeorje.cli.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.swarm.RemoteAttachment;
import org.junit.jupiter.api.Test;

class RemoteAgentTest {

    /** The stub host: it holds the sink of the client and records what the client sent. */
    static final class StubHost {
        final List<String> steered = new CopyOnWriteArrayList<>();
        final List<String> answered = new CopyOnWriteArrayList<>();
        volatile Consumer<String> sink;
        volatile Runnable closedCallback;
        volatile boolean detached;
        volatile String refuse;

        RemoteAgent.Attacher attacher(List<String> history) {
            return (peer, agent, onLine, onClosed) -> {
                if (refuse != null) {
                    var failed = new CompletableFuture<List<String>>();
                    failed.completeExceptionally(new IllegalStateException(refuse));
                    return attachment(failed);
                }
                sink = onLine;
                closedCallback = onClosed;
                return attachment(CompletableFuture.completedFuture(history));
            };
        }

        RemoteAttachment attachment(CompletableFuture<List<String>> opened) {
            return new RemoteAttachment() {
                @Override public CompletableFuture<List<String>> opened() { return opened; }

                @Override public void steer(String text) { steered.add(text); }

                @Override public CompletableFuture<Void> answer(String requestId, String text) {
                    answered.add(requestId + "=" + text);
                    return CompletableFuture.completedFuture(null);
                }

                @Override public void close() { detached = true; }
            };
        }
    }

    @Test
    void historyArrivesLinesGrowSteerAndAnswerReachTheHost() {
        var host = new StubHost();
        var seen = new CopyOnWriteArrayList<String>();
        try (var agent = new RemoteAgent(host.attacher(List.of("> hi", "hello"))).onLine(seen::add)
                .attach("peer-1", "main")) {
            assertEquals(List.of("> hi", "hello"), agent.history());
            assertEquals("peer-1", agent.peerId());
            assertTrue(agent.lines().isEmpty());
            host.sink.accept("live 1");
            host.sink.accept("live 2");
            assertEquals(List.of("live 1", "live 2"), agent.lines());
            assertEquals(List.of("live 1", "live 2"), seen);
            agent.steer("stop");
            agent.answer("req-1", "yes");
            assertEquals(List.of("stop"), host.steered);
            assertEquals(List.of("req-1=yes"), host.answered);
        }
        assertTrue(host.detached);
    }

    @Test
    void closeReleasesTheAttachmentAndLaterLinesAreDropped() {
        var host = new StubHost();
        var agent = new RemoteAgent(host.attacher(List.of())).attach("p", "main");
        host.sink.accept("before");
        agent.close();
        assertTrue(host.detached);
        assertTrue(agent.closed());
        host.sink.accept("after");
        assertEquals(List.of("before"), agent.lines());
        agent.steer("ignored");
        assertTrue(host.steered.isEmpty());
        assertThrows(IllegalStateException.class, () -> agent.answer("r", "yes"));
    }

    @Test
    void theHostCanEndTheAttachment() {
        var host = new StubHost();
        var ended = new CopyOnWriteArrayList<String>();
        var agent = new RemoteAgent(host.attacher(List.of())).onClosed(() -> ended.add("closed")).attach("p", "main");
        host.closedCallback.run();
        assertTrue(agent.closed());
        assertEquals(List.of("closed"), ended);
        host.sink.accept("late");
        assertTrue(agent.lines().isEmpty());
    }

    @Test
    void aRefusedAttachFailsWithTheReason() {
        var host = new StubHost();
        host.refuse = "unknown agent: ghost";
        var e = assertThrows(IllegalStateException.class,
                () -> new RemoteAgent(host.attacher(List.of())).attach("p", "ghost"));
        assertTrue(e.getMessage().contains("unknown agent"));
        assertTrue(host.detached);
    }

    @Test
    void theInterfaceIdKeepsThePeerAndTheAgent() {
        var id = RemoteAgent.idOf("1234-abcd", "worker-1");
        assertEquals("peer:1234-abcd/worker-1", id);
        assertEquals("1234-abcd", RemoteAgent.peerOf(id));
        assertEquals("worker-1", RemoteAgent.agentOf(id));
        assertNull(RemoteAgent.peerOf("peer:1234-abcd"));
        assertNull(RemoteAgent.agentOf("main"));
        assertFalse(RemoteAgent.idOf("p", "a").isBlank());
    }
}
