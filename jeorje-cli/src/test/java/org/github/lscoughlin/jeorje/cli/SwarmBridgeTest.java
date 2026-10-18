package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.github.lscoughlin.jeorje.core.agent.AgentInput;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;
import org.github.lscoughlin.jeorje.core.port.Hit;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.subagent.Completion;
import org.github.lscoughlin.jeorje.core.subagent.SpawnException;
import org.github.lscoughlin.jeorje.core.subagent.SpawnRequest;
import org.github.lscoughlin.jeorje.core.subagent.SubagentEvent;
import org.github.lscoughlin.jeorje.core.subagent.SubagentLimits;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;
import org.github.lscoughlin.jeorje.swarm.PeerRecord;
import org.github.lscoughlin.jeorje.swarm.PeerState;
import org.github.lscoughlin.jeorje.swarm.SwarmConfig;
import org.github.lscoughlin.jeorje.testkit.swarm.InMemoryNetwork;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Two instances in one JVM on an in-memory network: one delegates a task to the other. */
class SwarmBridgeTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String SUMMARY = """
        Outcome: completed
        Done: Ran the build.
        Files: none
        Branch: none
        Decisions: Used the offline profile.
        Verification: ran mvn test, it passed.
        Open issues: none
        Keywords: build, offline, mvn""";

    @TempDir Path dir;
    private final InMemoryNetwork network = new InMemoryNetwork();
    private final List<Instance> instances = new ArrayList<>();

    @AfterEach
    void close() {
        instances.forEach(Instance::close);
        network.close();
    }

    /** A model that plays steps. A step can wait for a latch and can record its request. */
    static final class Model implements LlmClient {
        sealed interface Step {}
        record Say(String text) implements Step {}
        record CallTool(String id, String tool) implements Step {}
        record WaitForCancel(CountDownLatch started) implements Step {}

        final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
        private final List<Step> steps;
        private final AtomicInteger next = new AtomicInteger();

        Model(Step... steps) { this.steps = List.of(steps); }

        @Override
        public Flow.Publisher<LlmEvent> stream(LlmRequest request, CancellationToken cancel) {
            requests.add(request);
            var step = next.getAndIncrement() < steps.size() ? steps.get(next.get() - 1) : new Say(SUMMARY);
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    switch (step) {
                        case Say s -> {
                            subscriber.onNext(new LlmEvent.TextDelta(s.text()));
                            subscriber.onNext(new LlmEvent.MessageStop("end_turn"));
                        }
                        case CallTool c -> {
                            subscriber.onNext(new LlmEvent.ToolUseStart(c.id(), c.tool()));
                            subscriber.onNext(new LlmEvent.ToolUseInput(c.id(), "{}"));
                            subscriber.onNext(new LlmEvent.MessageStop("tool_use"));
                        }
                        case WaitForCancel w -> {
                            w.started().countDown();
                            var gone = new CountDownLatch(1);
                            cancel.onCancel(gone::countDown);
                            try {
                                gone.await(20, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                    subscriber.onComplete();
                }

                @Override
                public void cancel() { }
            });
        }

        String allRequestText() {
            var sb = new StringBuilder();
            for (var r : requests) {
                for (var m : r.messages()) m.content().forEach(c -> sb.append(c).append('\n'));
            }
            return sb.toString();
        }
    }

    static Tool tool(String name, java.util.concurrent.Callable<String> body) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "Tool " + name, MAPPER.createObjectNode().put("type", "object"));
            }

            @Override
            public boolean readOnly() { return false; }

            @Override
            public ToolResultData execute(tools.jackson.databind.JsonNode input, ToolContext context) throws Exception {
                return ToolResultData.ok(body.call());
            }
        };
    }

    /** One instance of Jeorje: a bridge, a runtime, a root agent, and the records of what happened. */
    final class Instance implements AutoCloseable {
        final String name;
        final SwarmBridge bridge;
        final SubagentRuntime runtime;
        final Model model;
        final List<SubagentEvent> subagentEvents = new CopyOnWriteArrayList<>();
        final List<AgentEvent> agentEvents = new CopyOnWriteArrayList<>();
        final List<AgentInput> mainInput = new CopyOnWriteArrayList<>();
        final List<Chunk> chunks = new CopyOnWriteArrayList<>();
        final List<Hit> hits = new CopyOnWriteArrayList<>();
        final AgentId main = new AgentId("main");

        Instance(String name, String token, List<String> wka, Model model, List<Tool> tools,
                 Predicate<String> historyAllowed, PermissionPolicy policy) {
            this.name = name;
            this.model = model;
            var config = SwarmConfig.defaults().withToken(token).withWka(wka).withGossipIntervalMs(30)
                    .withPingTimeoutMs(40).withSuspectTimeoutMs(500).withHandshakeTimeoutMs(500);
            var transport = network.join("mem:" + name, name, token);
            var self = PeerRecord.of(name, "mem:" + name, name);
            bridge = new SwarmBridge(config, transport, self, Clock.systemUTC(), historyAllowed, text -> { }, null);
            var store = new ContextStore() {
                @Override public void append(List<Chunk> c) { chunks.addAll(c); }
                @Override public List<Hit> search(HistoryQuery query) { return List.copyOf(hits); }
                @Override public Optional<Chunk> get(String chunkId) { return Optional.empty(); }
                @Override public List<Chunk> window(String chunkId, int before, int after) { return List.of(); }
                @Override public void close() { }
            };
            var settings = AgentSettings.defaults("s-" + name, dir, 100_000).withSleeper(d -> { });
            runtime = SubagentRuntime.builder("s-" + name, settings, m -> model).store(store)
                    .agentEvents(e -> {
                        agentEvents.add(e);
                        bridge.onAgentEvent(e);
                    })
                    .events(e -> {
                        subagentEvents.add(e);
                        bridge.onSubagentEvent(e);
                    })
                    .limits(SubagentLimits.defaults().withAskTimeout(Duration.ofSeconds(5)))
                    .remote(bridge).build();
            runtime.registerRoot(main, "main", new CancellationToken(), tools, policy, mainInput::add, () -> 1);
            bridge.attach(runtime, tools, policy, mainInput::add, () -> store);
            instances.add(this);
        }

        Instance start() {
            bridge.start();
            return this;
        }

        List<PeerRecord> alive() { return bridge.node().peers(PeerState.ALIVE); }

        @Override
        public void close() {
            bridge.close();
            runtime.close();
        }
    }

    private Instance instance(String name, String token, List<String> wka, Model model, List<Tool> tools,
                              Predicate<String> historyAllowed, PermissionPolicy policy) {
        return new Instance(name, token, wka, model, tools, historyAllowed, policy).start();
    }

    private static final PermissionPolicy ALLOW = action -> PermissionPolicy.Decision.ALLOW;

    private static void await(BooleanSupplier condition, String what) {
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("not reached in 15 s: " + what);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private Instance[] pair(Model remoteModel, List<Tool> remoteTools, Predicate<String> historyAllowed,
                            PermissionPolicy remotePolicy) {
        var local = instance("local", "secret", List.of(), new Model(), List.of(), p -> false, ALLOW);
        var remote = instance("remote", "secret", List.of("mem:local"), remoteModel, remoteTools, historyAllowed,
                remotePolicy);
        await(() -> local.alive().size() == 1 && remote.alive().size() == 1, "the peers see each other");
        return new Instance[] {local, remote};
    }

    @Test
    void aTaskRunsOnThePeerAndTheLocalSideShowsEventsAndWritesTheSummaryChunk() throws Exception {
        var model = new Model(new Model.CallTool("c1", "build"), new Model.Say("The build ran."),
                new Model.Say(SUMMARY));
        var p = pair(model, List.of(tool("build", () -> "built")), x -> false, ALLOW);
        var local = p[0];
        var remote = p[1];

        // The agent of the user calls spawn_agent with a peer. The tool wrapper starts the remote subagent.
        var spawn = new PeerSpawnTool(tool("spawn_agent", () -> "local"), local.runtime);
        var input = MAPPER.createObjectNode().put("name", "builder").put("task", "run the build")
                .put("peer", "remote").put("mode", "wait");
        input.putArray("tools").add("build");
        var context = new ToolContext(dir, local.main, new CancellationToken(), null, Clock.systemUTC());
        var result = spawn.execute(input, context);

        assertFalse(result.error(), result.output());
        assertTrue(result.output().contains("Outcome: completed"), result.output());
        assertTrue(result.output().contains("The build ran."), result.output());

        // The local side shows the remote subagent as a local subagent.
        assertTrue(local.subagentEvents.stream().anyMatch(e -> e instanceof SubagentEvent.Spawned s
                && s.name().equals("builder")));
        var finished = local.subagentEvents.stream().filter(SubagentEvent.Finished.class::isInstance)
                .map(SubagentEvent.Finished.class::cast).findFirst().orElseThrow();
        assertEquals(Completion.Outcome.COMPLETED, finished.completion().outcome());
        var subId = finished.agent();
        assertTrue(local.agentEvents.stream().anyMatch(e -> e instanceof AgentEvent.TokenDelta d
                && d.agent().equals(subId) && d.text().contains("build")), "progress shows in the local view");

        // The summary is a subagent_summary chunk in the history of the local instance.
        var chunk = local.chunks.stream().filter(c -> SubagentRuntime.SUMMARY_KIND.equals(c.kind())).findFirst()
                .orElseThrow();
        assertTrue(chunk.text().contains("The build ran."), chunk.text());
        assertEquals(subId.value(), chunk.sourceAgentId());

        // The peer ran the task with its own tools, in its own runtime.
        assertTrue(model.requests.getFirst().tools().stream().anyMatch(t -> t.name().equals("build")));
        assertTrue(model.requests.getFirst().system().contains("run the build"));
        assertTrue(remote.runtime.status().stream().anyMatch(i -> i.name().startsWith("peer-local-")));
    }

    @Test
    void aBackgroundTaskOnAPeerWithACapabilityReturnsTheSummaryAsAnInputEvent() throws Exception {
        var model = new Model(new Model.Say("done"), new Model.Say(SUMMARY));
        var p = pair(model, List.of(tool("build", () -> "built")), x -> false, ALLOW);
        var local = p[0];
        await(() -> local.bridge.node().findPeer("tools:build").isPresent(), "the capability reaches the peer list");
        var id = local.runtime.spawn(local.main, SpawnRequest.of("scan", "scan the repo")
                .withCapability("tools:build"));
        var completion = local.runtime.await(id, new CancellationToken());
        assertEquals(Completion.Outcome.COMPLETED, completion.outcome());
        assertTrue(local.mainInput.stream().anyMatch(i -> i instanceof AgentInput.SubagentResult r
                && r.text().contains("Outcome: completed")), local.mainInput.toString());
    }

    @Test
    void steeringReachesTheRemoteAgentAtItsNextStepBoundary() throws Exception {
        var release = new CountDownLatch(1);
        var model = new Model(new Model.CallTool("c1", "probe"), new Model.Say("continuing"), new Model.Say(SUMMARY));
        var probe = tool("probe", () -> {
            release.await(20, TimeUnit.SECONDS);
            return "probed";
        });
        var p = pair(model, List.of(probe), x -> false, ALLOW);
        var local = p[0];
        var id = local.runtime.spawn(local.main, SpawnRequest.of("worker", "do the work").withPeer("remote")
                .withTools(List.of("probe")));
        await(() -> remote(p).runtime.status().stream().anyMatch(i -> i.name().startsWith("peer-local-")
                && i.lastOutput() != null) && model.requests.size() == 1, "the remote agent made its first call");
        assertTrue(local.runtime.steer(id, "use the offline profile"));
        Thread.sleep(400);
        release.countDown();
        var completion = local.runtime.await(id, new CancellationToken());
        assertEquals(Completion.Outcome.COMPLETED, completion.outcome());
        assertTrue(model.requests.get(1).messages().toString().contains("use the offline profile"),
                "the second request of the remote agent holds the steering text");
    }

    @Test
    void cancelReachesTheRemoteAgentAndBothSidesEndCancelled() throws Exception {
        var started = new CountDownLatch(1);
        var model = new Model(new Model.WaitForCancel(started));
        var p = pair(model, List.of(), x -> false, ALLOW);
        var local = p[0];
        var id = local.runtime.spawn(local.main, SpawnRequest.of("slow", "wait forever").withPeer("remote"));
        assertTrue(started.await(10, TimeUnit.SECONDS));
        assertTrue(local.runtime.cancel("slow"));
        var completion = local.runtime.await(id, new CancellationToken());
        assertEquals(Completion.Outcome.CANCELLED, completion.outcome());
        await(() -> remote(p).runtime.status().stream().anyMatch(i -> i.name().startsWith("peer-local-")
                && i.state() == org.github.lscoughlin.jeorje.core.subagent.AgentLifecycle.CANCELLED),
                "the remote agent is cancelled");
        assertTrue(local.chunks.stream().anyMatch(c -> SubagentRuntime.SUMMARY_KIND.equals(c.kind())));
    }

    @Test
    void thePolicyOfThePeerCanRejectATask() throws Exception {
        PermissionPolicy denyBuild = action -> action.tool().equals("build") ? PermissionPolicy.Decision.DENY
                : PermissionPolicy.Decision.ALLOW;
        var p = pair(new Model(), List.of(tool("build", () -> "built")), x -> false, denyBuild);
        var local = p[0];
        var id = local.runtime.spawn(local.main, SpawnRequest.of("blocked", "run the build").withPeer("remote")
                .withTools(List.of("build")));
        var completion = local.runtime.await(id, new CancellationToken());
        assertEquals(Completion.Outcome.FAILED, completion.outcome());
        assertTrue(completion.reason().contains("denies"), completion.reason());
        assertTrue(remote(p).runtime.status().stream().noneMatch(i -> i.name().startsWith("peer-local-")),
                "the peer did not start a subagent");
    }

    @Test
    void aWrongTokenIsRejected() throws Exception {
        var a = instance("a", "alpha", List.of(), new Model(), List.of(), x -> false, ALLOW);
        var b = instance("b", "beta", List.of("mem:a"), new Model(), List.of(), x -> false, ALLOW);
        await(() -> network.rejected() > 0, "the handshake fails");
        Thread.sleep(300);
        assertTrue(a.alive().isEmpty());
        assertTrue(b.alive().isEmpty());
        var e = assertThrows(SpawnException.class,
                () -> b.runtime.spawn(b.main, SpawnRequest.of("x", "task").withPeer("a")));
        assertTrue(e.getMessage().contains("No live peer"), e.getMessage());
    }

    @Test
    void historyQueriesAreDeniedByDefault() throws Exception {
        var p = pair(new Model(), List.of(), SwarmBridge.historyRule(configWithRules("").config()), ALLOW);
        p[1].hits.add(new Hit(chunk("remote:1.0", "secret walrus text"), 1.0f, "secret walrus text"));
        var result = p[0].bridge.node().historyQuery("remote", "walrus", 5).get(10, TimeUnit.SECONDS);
        assertFalse(result.allowed());
        assertTrue(result.hits().isEmpty());
    }

    @Test
    void historyQueriesAreAnsweredWhenAUserRuleAllowsThePeer() throws Exception {
        var rules = """
            permissions:
              rules:
                - tool: history_query
                  pattern: local
                  decision: allow
            """;
        var p = pair(new Model(), List.of(), SwarmBridge.historyRule(configWithRules(rules).config()), ALLOW);
        p[1].hits.add(new Hit(chunk("remote:1.0", "walrus text"), 0.5f, "walrus snippet"));
        var result = p[0].bridge.node().historyQuery("remote", "walrus", 5).get(10, TimeUnit.SECONDS);
        assertTrue(result.allowed());
        assertEquals(1, result.hits().size());
        assertEquals("walrus snippet", result.hits().getFirst().snippet());
        assertEquals("remote", result.hits().getFirst().agentId());
    }

    @Test
    void aMessageFromAPeerGoesToTheAgentOfTheUserAndNeverApprovesAnything() throws Exception {
        var p = pair(new Model(), List.of(), x -> false, ALLOW);
        p[0].bridge.node().sendAgentMessage("remote", "main", "please look at the build");
        await(() -> !p[1].mainInput.isEmpty(), "the message arrives");
        var input = p[1].mainInput.getFirst();
        assertTrue(input instanceof AgentInput.PeerMessage);
        assertTrue(input.text().contains("please look at the build"));
    }

    private JeorjeConfigHolder configWithRules(String yaml) throws Exception {
        var file = dir.resolve("rules-" + System.nanoTime() + ".yaml");
        java.nio.file.Files.writeString(file, yaml.isEmpty() ? "swarm:\n  enabled: true\n" : yaml);
        var holder = new Holder();
        new picocli.CommandLine(holder).parseArgs("--config", file.toString());
        var env = new CliEnvironment(dir.resolve("home"), dir, java.util.Map.of(), System.out, System.err, null, null,
                false);
        return new JeorjeConfigHolder(Wiring.loadConfig(holder.global, env).config());
    }

    /** Holds the global options so that picocli can fill them. */
    static final class Holder {
        @picocli.CommandLine.Mixin
        GlobalOptions global = new GlobalOptions();
    }

    record JeorjeConfigHolder(org.github.lscoughlin.jeorje.core.config.JeorjeConfig config) {}

    private static Instance remote(Instance[] pair) { return pair[1]; }

    private static Chunk chunk(String id, String text) {
        return new Chunk(id, "s", "remote", 1, 0, java.time.Instant.now(), "mixed", null, text);
    }
}
