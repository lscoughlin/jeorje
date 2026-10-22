package org.github.lscoughlin.jeorje.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AttachTest {

    /** A host that records what the client sent. */
    static final class StubHost implements SwarmHandlers.ForegroundHost {
        final List<String> steered = new CopyOnWriteArrayList<>();
        final List<String> answered = new CopyOnWriteArrayList<>();
        final AtomicInteger detached = new AtomicInteger();
        volatile Consumer<String> sink;

        @Override public List<String> history() { return List.of("old 1", "old 2"); }

        @Override public String agentId() { return "main"; }

        @Override public void steer(String text) { steered.add(text); }

        @Override public void answer(String requestId, String text) {
            if (text.equals("refuse")) throw new IllegalStateException("not waiting for " + requestId);
            answered.add(requestId + "=" + text);
        }

        @Override public void onLine(Consumer<String> value) { this.sink = value; }

        @Override public void detach() {
            detached.incrementAndGet();
            sink = null;
        }

        void emit(String line) {
            var current = sink;
            if (current != null) current.accept(line);
        }
    }

    private static SwarmHandlers hostHandlers(StubHost host) {
        return new SwarmHandlers() {
            @Override
            public ForegroundHost attach(String fromPeer, String agentId) {
                return agentId.equals("main") ? host : null;
            }

            @Override
            public List<PeerAgent> roster() {
                return List.of(new PeerAgent("main", "main", "", "IDLE"), new PeerAgent("s1", "worker", "main", "RUNNING"));
            }
        };
    }

    @Test
    void aClientAttachesReadsHistoryAndLinesSteersAndAnswers() throws Exception {
        try (var cluster = new Cluster()) {
            var host = new StubHost();
            var a = cluster.add("a", List.of());
            cluster.add("b", List.of("a"), "", hostHandlers(host));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));

            var lines = new CopyOnWriteArrayList<String>();
            var attachment = a.attach("b", "main", lines::add, () -> { });
            assertEquals(List.of("old 1", "old 2"), attachment.opened().get(5, TimeUnit.SECONDS));
            host.emit("live 1");
            host.emit("live 2");
            assertTrue(Cluster.await(() -> lines.size() == 2, 5_000));
            assertEquals(List.of("live 1", "live 2"), lines);

            attachment.steer("please stop");
            assertTrue(Cluster.await(() -> host.steered.contains("please stop"), 5_000));
            attachment.answer("req-1", "yes").get(5, TimeUnit.SECONDS);
            assertEquals(List.of("req-1=yes"), host.answered);
            var refused = assertThrows(ExecutionException.class,
                    () -> attachment.answer("req-2", "refuse").get(5, TimeUnit.SECONDS));
            assertTrue(refused.getCause().getMessage().contains("not waiting"), refused.getCause().getMessage());

            attachment.close();
            assertTrue(Cluster.await(() -> host.detached.get() == 1, 5_000));
            host.emit("after close");
            Thread.sleep(150);
            assertEquals(2, lines.size());
        }
    }

    @Test
    void anAttachToAnUnknownAgentGetsAnErrorFrame() throws Exception {
        try (var cluster = new Cluster()) {
            var host = new StubHost();
            var a = cluster.add("a", List.of());
            cluster.add("b", List.of("a"), "", hostHandlers(host));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));
            var attachment = a.attach("b", "nobody", line -> { }, () -> { });
            var error = assertThrows(ExecutionException.class, () -> attachment.opened().get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("unknown agent"), error.getCause().getMessage());
            assertNull(host.sink);
            // The attachment is closed: steering goes nowhere and a late line does not arrive.
            attachment.steer("ignored");
            Thread.sleep(100);
            assertTrue(host.steered.isEmpty());
        }
    }

    @Test
    void theRosterListsTheAgentsOfThePeer() throws Exception {
        try (var cluster = new Cluster()) {
            var a = cluster.add("a", List.of());
            cluster.add("b", List.of("a"), "", hostHandlers(new StubHost()));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));
            var roster = a.roster("b").get(5, TimeUnit.SECONDS);
            assertEquals(2, roster.size());
            assertEquals("s1", roster.get(1).agentId());
            assertEquals("main", roster.get(1).parentId());
        }
    }

    @Test
    void aPeerWithoutHandlersHasNoAgentsAndRefusesAttach() throws Exception {
        try (var cluster = new Cluster()) {
            var a = cluster.add("a", List.of());
            cluster.add("b", List.of("a"));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));
            assertTrue(a.roster("b").get(5, TimeUnit.SECONDS).isEmpty());
            var attachment = a.attach("b", "main", line -> { }, () -> { });
            assertThrows(ExecutionException.class, () -> attachment.opened().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void attachFramesOnTheTcpTransportAreIgnored() throws Exception {
        try (var cluster = new Cluster()) {
            var host = new StubHost();
            var a = cluster.add("a", List.of());
            cluster.add("b", List.of("a"), "", hostHandlers(host));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));
            var send = SwarmNode.class.getDeclaredMethod("sendTo", String.class, String.class, Object.class);
            send.setAccessible(true);
            send.invoke(a, Cluster.address("b"), Messages.AGENT_ATTACH,
                    new Messages.AttachBody("x1", "open", "main", List.of(), null));
            Thread.sleep(300);
            assertNull(host.sink, "the attach over TCP must not reach the host");
        }
    }
}
