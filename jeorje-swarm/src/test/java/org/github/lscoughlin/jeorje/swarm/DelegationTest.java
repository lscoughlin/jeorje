package org.github.lscoughlin.jeorje.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.swarm.Messages.Delegate;
import org.github.lscoughlin.jeorje.swarm.Messages.HistoryHitView;
import org.github.lscoughlin.jeorje.swarm.Messages.Limits;
import org.github.lscoughlin.jeorje.swarm.SwarmHandlers.DelegateHost;
import org.github.lscoughlin.jeorje.swarm.SwarmHandlers.DelegateRejectedException;
import org.github.lscoughlin.jeorje.swarm.SwarmHandlers.RunningTask;
import org.junit.jupiter.api.Test;

class DelegationTest {

    /** A fake remote subagent. It keeps what it receives and finishes on command. */
    static final class FakeHost implements SwarmHandlers, DelegateHost {
        final Map<String, FakeTask> tasks = new ConcurrentHashMap<>();
        final List<String> policyAsked = new CopyOnWriteArrayList<>();
        volatile boolean deny;

        @Override
        public DelegateHost delegateHost() {
            return this;
        }

        @Override
        public RunningTask accept(String fromPeer, Delegate request, Consumer<DelegateEvent> sink) {
            policyAsked.add(request.task());
            if (deny) {
                throw new DelegateRejectedException("policy denies: " + request.task());
            }
            var task = new FakeTask(request, sink);
            tasks.put(request.taskId(), task);
            sink.accept(DelegateEvent.progress(request.taskId(), "started " + request.task()));
            return task;
        }
    }

    static final class FakeTask implements RunningTask {
        final Delegate request;
        final Consumer<DelegateEvent> sink;
        final List<String> steering = new CopyOnWriteArrayList<>();
        final List<String> answers = new CopyOnWriteArrayList<>();
        volatile boolean cancelled;

        FakeTask(Delegate request, Consumer<DelegateEvent> sink) {
            this.request = request;
            this.sink = sink;
        }

        @Override
        public void steer(String text) {
            steering.add(text);
            sink.accept(DelegateEvent.progress(request.taskId(), "steered: " + text));
        }

        @Override
        public void cancel() {
            cancelled = true;
            sink.accept(DelegateEvent.result(request.taskId(), "cancelled", "stopped", "Cancelled by the delegating peer."));
        }

        @Override
        public void answer(String questionId, String text) {
            answers.add(questionId + "=" + text);
        }
    }

    private record Pair(Cluster cluster, SwarmNode local, SwarmNode remote, FakeHost host) {}

    private Pair pair() {
        var cluster = new Cluster();
        var host = new FakeHost();
        var local = cluster.add("local", List.of());
        var remote = cluster.add("remote", List.of("local"), "", host, List.of("tools:bash"));
        assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));
        return new Pair(cluster, local, remote, host);
    }

    @Test
    void remoteSubagentRunsSteersAndSendsTheSummaryInTheLastEvent() throws Exception {
        var p = pair();
        try (var cluster = p.cluster()) {
            var events = new CopyOnWriteArrayList<DelegateEvent>();
            var peer = p.local().findPeer("tools:bash").orElseThrow();
            var delegation = p.local().delegate(peer.peerId(), "run the build", List.of("bash"),
                    new Limits(10, 60), events::add);
            assertTrue(Cluster.await(() -> p.host().tasks.containsKey(delegation.taskId()), 5_000));
            var task = p.host().tasks.get(delegation.taskId());
            assertEquals(List.of("bash"), task.request.allowedTools());
            assertEquals(10, task.request.limits().maxTurns());

            delegation.steer("use the offline profile");
            assertTrue(Cluster.await(() -> events.stream().anyMatch(e -> "steered: use the offline profile".equals(e.text())), 5_000));

            task.sink.accept(DelegateEvent.result(delegation.taskId(), "completed", "build ok",
                    "Outcome: completed. Build passed."));
            var last = delegation.completion().get(5, TimeUnit.SECONDS);
            assertTrue(last.last());
            assertEquals("completed", last.outcome());
            assertEquals("Outcome: completed. Build passed.", last.summary());
            assertEquals(DelegateEvent.Kind.PROGRESS, events.get(0).kind());
            assertEquals("started run the build", events.get(0).text());
            assertEquals("steered: use the offline profile", events.get(1).text());
            assertEquals(last, events.get(events.size() - 1));
            for (var i = 1; i < events.size(); i++) {
                assertTrue(events.get(i).seq() > events.get(i - 1).seq(), "events keep their order");
            }
        }
    }

    @Test
    void cancelStopsTheRemoteSubagent() throws Exception {
        var p = pair();
        try (var cluster = p.cluster()) {
            var delegation = p.local().delegate("remote", "long job", List.of(), new Limits(0, 0), e -> {});
            assertTrue(Cluster.await(() -> p.host().tasks.containsKey(delegation.taskId()), 5_000));
            delegation.cancel();
            var last = delegation.completion().get(5, TimeUnit.SECONDS);
            assertEquals("cancelled", last.outcome());
            assertTrue(p.host().tasks.get(delegation.taskId()).cancelled);
        }
    }

    @Test
    void remoteQuestionIsAnsweredByTheDelegatingPeer() throws Exception {
        var p = pair();
        try (var cluster = p.cluster()) {
            var events = new CopyOnWriteArrayList<DelegateEvent>();
            var delegation = p.local().delegate("remote", "task", List.of(), new Limits(0, 0), events::add);
            assertTrue(Cluster.await(() -> p.host().tasks.containsKey(delegation.taskId()), 5_000));
            var task = p.host().tasks.get(delegation.taskId());
            task.sink.accept(DelegateEvent.question(delegation.taskId(), "q1", "which branch?"));
            assertTrue(Cluster.await(() -> events.stream().anyMatch(e -> "q1".equals(e.questionId())), 5_000));
            delegation.answer("q1", "main");
            assertTrue(Cluster.await(() -> task.answers.contains("q1=main"), 5_000));
        }
    }

    @Test
    void policyDenialReturnsARejection() throws Exception {
        var p = pair();
        try (var cluster = p.cluster()) {
            p.host().deny = true;
            var delegation = p.local().delegate("remote", "rm -rf", List.of(), new Limits(0, 0), e -> {});
            var last = delegation.completion().get(5, TimeUnit.SECONDS);
            assertEquals("rejected", last.outcome());
            assertEquals(DelegateEvent.Kind.REJECTED, last.kind());
            assertTrue(last.text().contains("policy denies"));
        }
    }

    @Test
    void aPeerWithNoHostRejectsDelegation() throws Exception {
        try (var cluster = new Cluster()) {
            var local = cluster.add("local", List.of());
            cluster.add("plain", List.of("local"));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));
            var last = local.delegate("plain", "task", List.of(), new Limits(0, 0), e -> {}).completion()
                    .get(5, TimeUnit.SECONDS);
            assertEquals("rejected", last.outcome());
        }
    }

    @Test
    void lostPeerFailsTheDelegation() throws Exception {
        var p = pair();
        try (var cluster = p.cluster()) {
            var delegation = p.local().delegate("remote", "task", List.of(), new Limits(0, 0), e -> {});
            assertTrue(Cluster.await(() -> p.host().tasks.containsKey(delegation.taskId()), 5_000));
            p.remote().kill();
            var error = assertThrows(ExecutionException.class,
                    () -> delegation.completion().get(10, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof SwarmNode.PeerLostException);
        }
    }

    @Test
    void onlyTheDelegatingPeerCanSteerATask() throws Exception {
        try (var cluster = new Cluster()) {
            var host = new FakeHost();
            var boss = cluster.add("boss", List.of());
            cluster.add("worker", List.of("boss"), "", host);
            var other = cluster.add("other", List.of("boss"));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));
            var delegation = boss.delegate("worker", "task", List.of(), new Limits(0, 0), e -> {});
            assertTrue(Cluster.await(() -> host.tasks.containsKey(delegation.taskId()), 5_000));
            var task = host.tasks.get(delegation.taskId());
            // The other peer knows the task id and tries to steer and cancel.
            var forged = other.delegate("worker", "x", List.of(), new Limits(0, 0), e -> {});
            assertTrue(Cluster.await(() -> host.tasks.containsKey(forged.taskId()), 5_000));
            var spoof = new Delegation(other, "worker", delegation.taskId());
            spoof.steer("evil");
            spoof.cancel();
            Cluster.await(() -> false, 300);
            assertTrue(task.steering.isEmpty());
            assertFalse(task.cancelled);
        }
    }

    /** Sends raw steering and cancel messages for a task id that belongs to someone else. */
    private record Delegation(SwarmNode from, String peer, String taskId) {
        void steer(String text) {
            send("steer", text);
        }

        void cancel() {
            send("cancel", null);
        }

        private void send(String kind, String text) {
            try {
                var method = SwarmNode.class.getDeclaredMethod("sendTo", String.class, String.class, Object.class);
                method.setAccessible(true);
                method.invoke(from, Cluster.address(peer), Messages.AGENT_MESSAGE,
                        new Messages.AgentPayload(kind, null, taskId, null, null, text));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    void agentMessageAndQuestionWithId() throws Exception {
        try (var cluster = new Cluster()) {
            var received = new CopyOnWriteArrayList<String>();
            var handlers = new SwarmHandlers() {
                @Override
                public void onAgentMessage(String fromPeer, String toAgent, String text) {
                    received.add(fromPeer + "/" + toAgent + ": " + text);
                }

                @Override
                public CompletionStage<String> onAgentAsk(String fromPeer, String toAgent, String question) {
                    return CompletableFuture.completedFuture("answer to " + question);
                }
            };
            var a = cluster.add("a", List.of());
            cluster.add("b", List.of("a"), "", handlers);
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));
            a.sendAgentMessage("b", "agent-1", "hello").get(5, TimeUnit.SECONDS);
            assertTrue(Cluster.await(() -> received.contains("a/agent-1: hello"), 5_000));
            assertEquals("answer to 2+2?", a.ask("b", "agent-1", "2+2?", Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void anAskWithNoAnswererFails() {
        try (var cluster = new Cluster()) {
            var a = cluster.add("a", List.of());
            cluster.add("b", List.of("a"));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));
            var error = assertThrows(ExecutionException.class,
                    () -> a.ask("b", null, "hi?", Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof IllegalStateException);
        }
    }

    @Test
    void historyQueryIsDeniedByDefaultAndAllowedByRule() throws Exception {
        try (var cluster = new Cluster()) {
            var a = cluster.add("a", List.of());
            cluster.add("denier", List.of("a"));
            cluster.add("allower", List.of("a"), "", new SwarmHandlers() {
                @Override
                public Optional<List<HistoryHitView>> onHistoryQuery(String fromPeer, String text, int limit) {
                    return Optional.of(List.of(new HistoryHitView("agent-7", "found " + text, 0.9f)));
                }
            });
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));
            var denied = a.historyQuery("denier", "bug", 5).get(5, TimeUnit.SECONDS);
            assertFalse(denied.allowed());
            assertTrue(denied.hits().isEmpty());
            var allowed = a.historyQuery("allower", "bug", 5).get(5, TimeUnit.SECONDS);
            assertTrue(allowed.allowed());
            assertEquals("found bug", allowed.hits().get(0).snippet());
            assertEquals("agent-7", allowed.hits().get(0).agentId());
        }
    }

    @Test
    void unknownMessageTypeIsIgnored() {
        try (var cluster = new Cluster()) {
            var a = cluster.add("a", List.of());
            var b = cluster.add("b", List.of("a"));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));
            var json = "{\"v\":1,\"type\":\"mystery\",\"from\":\"b\",\"fromAddr\":\"mem:b\",\"id\":\"1\",\"time\":0,\"body\":{}}";
            var transport = cluster.network.join("mem:raw", "b2");
            transport.send(a.address(), json.replace("\"b\"", "\"b2\""));
            Cluster.await(() -> false, 100);
            assertTrue(Cluster.allAlive(cluster.nodes));
            assertEquals(2, b.members().size());
        }
    }

    @Test
    void oversizedMessageIsRefusedByTheSender() {
        try (var cluster = new Cluster()) {
            cluster.base = cluster.base.withMaxMessageBytes(2000);
            var a = cluster.add("a", List.of());
            cluster.add("b", List.of("a"));
            assertTrue(Cluster.await(() -> Cluster.allAlive(cluster.nodes), 5_000));
            var error = assertThrows(ExecutionException.class,
                    () -> a.sendAgentMessage("b", null, "x".repeat(5000)).get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof IllegalArgumentException);
        }
    }
}
