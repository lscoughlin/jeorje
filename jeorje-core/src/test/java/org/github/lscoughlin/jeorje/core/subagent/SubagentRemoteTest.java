package org.github.lscoughlin.jeorje.core.subagent;

import static org.github.lscoughlin.jeorje.core.subagent.SubagentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.core.agent.AgentInput;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubagentRemoteTest {
    @TempDir Path dir;

    /** A fake swarm port. It keeps the calls and ends on command. */
    static final class FakeSpawner implements RemoteSpawner {
        final List<String> steering = new CopyOnWriteArrayList<>();
        final List<String> started = new CopyOnWriteArrayList<>();
        volatile boolean cancelled;
        volatile Consumer<Event> listener;
        final CompletableFuture<Event> completion = new CompletableFuture<>();
        volatile List<String> tools;

        @Override
        public Optional<Peer> resolve(String peer, String capability) {
            if ("worker-b".equals(peer) || "tools:bash".equals(capability)) return Optional.of(new Peer("b-id", "worker-b"));
            return Optional.empty();
        }

        @Override
        public Task start(Peer peer, String task, List<String> toolNames, int maxModelCalls, int timeoutSeconds,
                          Consumer<Event> events) {
            started.add(peer.id() + ":" + task);
            this.tools = toolNames;
            this.listener = events;
            return new Task() {
                @Override public void steer(String text) { steering.add(text); }
                @Override public void cancel() { cancelled = true; }
                @Override public void answer(String questionId, String text) { }
                @Override public CompletableFuture<Event> completion() { return completion; }
            };
        }

        void send(Event event) {
            listener.accept(event);
            if (event.last()) completion.complete(event);
        }
    }

    private record Setup(SubagentRuntime runtime, FakeSpawner spawner, List<SubagentEvent> events,
                         List<AgentInput> rootInput, List<AgentEvent> agentEvents, ListStore store) {}

    private Setup setup(boolean withSpawner) {
        var spawner = new FakeSpawner();
        var events = new CopyOnWriteArrayList<SubagentEvent>();
        var rootInput = new CopyOnWriteArrayList<AgentInput>();
        var agentEvents = new CopyOnWriteArrayList<AgentEvent>();
        var store = new ListStore();
        var settings = AgentSettings.defaults("s1", dir, 100_000);
        var builder = SubagentRuntime.builder("s1", settings, model -> { throw new IllegalStateException("no model"); })
            .store(store).events(events::add).agentEvents(agentEvents::add);
        if (withSpawner) builder.remote(spawner);
        var runtime = builder.build();
        runtime.registerRoot(new AgentId("root"), "root", new CancellationToken(), List.of(),
            a -> PermissionPolicy.Decision.ALLOW, rootInput::add, () -> 3);
        return new Setup(runtime, spawner, events, rootInput, agentEvents, store);
    }

    @Test
    void aRemoteSpawnShowsAsALocalSubagentAndEndsWithTheSummaryChunk() throws Exception {
        var s = setup(true);
        var request = SpawnRequest.of("remote-build", "run the build").withPeer("worker-b")
            .withTools(List.of("bash"));
        var id = s.runtime().spawn(new AgentId("root"), request);

        assertTrue(s.events().stream().anyMatch(e -> e instanceof SubagentEvent.Spawned sp && sp.agent().equals(id)));
        assertEquals(AgentLifecycle.RUNNING, s.runtime().info(id).orElseThrow().state());
        assertEquals(List.of("b-id:run the build"), s.spawner().started);
        assertEquals(List.of("bash"), s.spawner().tools);

        s.spawner().send(new RemoteSpawner.Event(RemoteSpawner.Kind.PROGRESS, "compiling", null, false, null, null));
        assertTrue(s.agentEvents().stream().anyMatch(e -> e instanceof AgentEvent.TokenDelta d
            && d.agent().equals(id) && d.text().contains("compiling")));
        assertEquals("compiling", s.runtime().info(id).orElseThrow().lastOutput());

        assertTrue(s.runtime().steer(id, "use the offline profile"));
        assertEquals(List.of("use the offline profile"), s.spawner().steering);

        s.runtime().cancel("remote-build");
        assertTrue(s.spawner().cancelled);

        s.spawner().send(new RemoteSpawner.Event(RemoteSpawner.Kind.RESULT, "stopped", null, true, "cancelled",
            "Outcome: cancelled. The task stopped."));
        var completion = s.runtime().await(id, CancellationToken.none());
        assertEquals(Completion.Outcome.CANCELLED, completion.outcome());
        assertTrue(completion.summary().contains("Outcome: cancelled. The task stopped."));
        assertEquals(AgentLifecycle.CANCELLED, s.runtime().info(id).orElseThrow().state());
        assertTrue(s.events().stream().anyMatch(e -> e instanceof SubagentEvent.Finished f && f.agent().equals(id)));

        var chunk = s.store().chunks.stream().filter(c -> SubagentRuntime.SUMMARY_KIND.equals(c.kind())).findFirst()
            .orElseThrow();
        assertTrue(chunk.text().contains("Outcome: cancelled. The task stopped."));
        assertTrue(chunk.text().contains("remote-build"));
        // A later steer to an ended agent fails.
        assertFalse(s.runtime().steer(id, "late"));
    }

    @Test
    void aCompletedBackgroundTaskGoesToTheParentAsAnInput() throws Exception {
        var s = setup(true);
        var id = s.runtime().spawn(new AgentId("root"),
            SpawnRequest.of("scan", "scan it").withCapability("tools:bash"));
        s.spawner().send(new RemoteSpawner.Event(RemoteSpawner.Kind.RESULT, "all done", null, true, "completed",
            "Outcome: completed. Found 3 files."));
        var completion = s.runtime().await(id, CancellationToken.none());
        assertEquals(Completion.Outcome.COMPLETED, completion.outcome());
        assertTrue(s.rootInput().stream().anyMatch(i -> i instanceof AgentInput.SubagentResult r
            && r.text().contains("Found 3 files.")));
    }

    @Test
    void aLostPeerFailsTheSubagent() throws Exception {
        var s = setup(true);
        var id = s.runtime().spawn(new AgentId("root"), SpawnRequest.of("scan", "scan it").withPeer("worker-b"));
        s.spawner().completion.completeExceptionally(new IllegalStateException("peer lost"));
        var completion = s.runtime().await(id, CancellationToken.none());
        assertEquals(Completion.Outcome.FAILED, completion.outcome());
        assertTrue(completion.reason().contains("peer lost"), completion.reason());
    }

    @Test
    void spawnFailsWithoutSwarmOrWithAnUnknownPeer() {
        var off = setup(false);
        var e1 = assertThrows(SpawnException.class,
            () -> off.runtime().spawn(new AgentId("root"), SpawnRequest.of("a", "t").withPeer("x")));
        assertTrue(e1.getMessage().contains("swarm is off"), e1.getMessage());
        var on = setup(true);
        var e2 = assertThrows(SpawnException.class,
            () -> on.runtime().spawn(new AgentId("root"), SpawnRequest.of("a", "t").withPeer("nobody")));
        assertTrue(e2.getMessage().contains("nobody"), e2.getMessage());
        var e3 = assertThrows(SpawnException.class,
            () -> on.runtime().spawn(new AgentId("root"), SpawnRequest.of("a", "t").withCapability("model:x")));
        assertTrue(e3.getMessage().contains("capability"), e3.getMessage());
    }
}
