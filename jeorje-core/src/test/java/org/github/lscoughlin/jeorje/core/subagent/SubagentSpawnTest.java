package org.github.lscoughlin.jeorje.core.subagent;

import static org.github.lscoughlin.jeorje.core.subagent.SubagentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.instructions.AgentTypeRegistry;
import org.github.lscoughlin.jeorje.core.instructions.Scopes;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubagentSpawnTest {
    @TempDir Path dir;

    private static ToolUse use(String tool) { return new ToolUse("u", tool, MAPPER.createObjectNode()); }

    private Rig rig(SubagentLimits limits, List<org.github.lscoughlin.jeorje.core.port.Tool> tools,
                    org.github.lscoughlin.jeorje.core.port.PermissionPolicy policy) {
        return new Rig(dir, limits, null, null, tools, policy);
    }

    // ---- life cycle ----

    @Test
    void lifecycleTableAllowsOnlyTheDesignedMoves() {
        assertTrue(AgentLifecycle.CREATED.canGo(AgentLifecycle.RUNNING));
        assertTrue(AgentLifecycle.RUNNING.canGo(AgentLifecycle.WAITING_INPUT));
        assertTrue(AgentLifecycle.RUNNING.canGo(AgentLifecycle.WAITING_AGENT));
        assertTrue(AgentLifecycle.RUNNING.canGo(AgentLifecycle.SYNCING));
        assertTrue(AgentLifecycle.SYNCING.canGo(AgentLifecycle.RESOLVING));
        assertTrue(AgentLifecycle.RESOLVING.canGo(AgentLifecycle.SYNCING));
        assertTrue(AgentLifecycle.SYNCING.canGo(AgentLifecycle.MERGING));
        assertTrue(AgentLifecycle.MERGING.canGo(AgentLifecycle.COMPLETED));
        assertTrue(AgentLifecycle.WAITING_AGENT.canGo(AgentLifecycle.RUNNING));
        assertFalse(AgentLifecycle.CREATED.canGo(AgentLifecycle.COMPLETED));
        assertFalse(AgentLifecycle.RUNNING.canGo(AgentLifecycle.MERGING));
        for (var terminal : List.of(AgentLifecycle.COMPLETED, AgentLifecycle.FAILED, AgentLifecycle.CANCELLED)) {
            assertTrue(terminal.terminal());
            for (var to : AgentLifecycle.values()) assertFalse(terminal.canGo(to), terminal + " to " + to);
        }
        for (var state : AgentLifecycle.values()) {
            if (!state.terminal()) {
                assertTrue(state.canGo(AgentLifecycle.FAILED));
                assertTrue(state.canGo(AgentLifecycle.CANCELLED));
            }
        }
    }

    @Test
    void spawnEventAndStateEventsAreEmitted() throws Exception {
        try (var rig = new Rig(dir)) {
            rig.model("m", new Scripted(text(WORK_TEXT), text(VALID)));
            var id = rig.spawn("worker", "m");
            var done = rig.await(id);
            var spawned = rig.events.stream().filter(SubagentEvent.Spawned.class::isInstance)
                .map(SubagentEvent.Spawned.class::cast).findFirst().orElseThrow();
            assertEquals("worker", spawned.name());
            assertEquals(rig.root, spawned.parent());
            assertEquals(1, spawned.depth());
            assertTrue(rig.events.stream().anyMatch(e -> e instanceof SubagentEvent.Finished f
                && f.completion().equals(done)));
            var first = (SubagentEvent.StateChanged) rig.events.stream()
                .filter(SubagentEvent.StateChanged.class::isInstance).findFirst().orElseThrow();
            assertEquals(AgentLifecycle.CREATED, first.from());
            assertEquals(AgentLifecycle.RUNNING, first.to());
        }
    }

    @Test
    void confirmationAndAskUserShowAsWaitingInput() throws Exception {
        var release = new CountDownLatch(1);
        var asking = tool("ask_user", false, (in, ctx) -> {
            release.await(10, TimeUnit.SECONDS);
            return org.github.lscoughlin.jeorje.core.model.ToolResultData.ok("yes");
        });
        try (var rig = rig(SubagentLimits.defaults(), List.of(asking), a -> Decision.ALLOW)) {
            rig.model("m", new Scripted(call("c1", "ask_user", "{}"), text(WORK_TEXT), text(VALID)));
            var id = rig.spawn("worker", "m");
            waitUntil(() -> rig.runtime.info(id).orElseThrow().state() == AgentLifecycle.WAITING_INPUT);
            release.countDown();
            rig.await(id);
            assertEquals(List.of(AgentLifecycle.RUNNING, AgentLifecycle.WAITING_INPUT, AgentLifecycle.RUNNING,
                AgentLifecycle.COMPLETED), rig.states(id));
        }
    }

    // ---- limits ----

    @Test
    void concurrencyLimitRefusesTheNinthSubagent() throws Exception {
        try (var rig = rig(SubagentLimits.defaults().withMaxConcurrent(2), List.of(), a -> Decision.ALLOW)) {
            var emitted = new CountDownLatch(2);
            rig.model("h", new Scripted(hang(emitted)));
            rig.model("h2", new Scripted(hang(emitted)));
            var a = rig.spawn("a", "h");
            rig.spawn("b", "h2");
            var e = assertThrows(SpawnException.class, () -> rig.spawn("c", "h"));
            assertTrue(e.getMessage().contains("2 subagents run at the same time"));
            rig.runtime.cancel("a");
            rig.await(a);
            rig.model("m", new Scripted(text(WORK_TEXT), text(VALID)));
            rig.await(rig.spawn("c", "m"));
        }
    }

    @Test
    void defaultLimitsMatchTheDesign() {
        var limits = SubagentLimits.defaults();
        assertEquals(8, limits.maxConcurrent());
        assertEquals(2, limits.maxDepth());
        assertEquals(100, limits.maxModelCalls());
        assertEquals(Duration.ofMinutes(30), limits.maxDuration());
        assertEquals(Duration.ofSeconds(120), limits.askTimeout());
        assertEquals(10, limits.maxAsks());
    }

    @Test
    void depthLimitRefusesTheThirdLevel() throws Exception {
        try (var rig = new Rig(dir)) {
            rig.model("h", new Scripted(hang(new CountDownLatch(1))));
            rig.model("g", new Scripted(hang(new CountDownLatch(1))));
            var a = rig.spawn("a", "h");
            var g = rig.runtime.spawn(a, SpawnRequest.of("grandchild", "Work").withModel("g"));
            var e = assertThrows(SpawnException.class,
                () -> rig.runtime.spawn(g, SpawnRequest.of("too-deep", "Work").withModel("g")));
            assertTrue(e.getMessage().contains("Depth limit"), e.getMessage());
            assertEquals(2, rig.runtime.info(g).orElseThrow().depth());
        }
    }

    @Test
    void modelCallLimitFailsTheSubagent() throws Exception {
        var loop = tool("t", true, (in, ctx) -> org.github.lscoughlin.jeorje.core.model.ToolResultData.ok("again"));
        try (var rig = rig(SubagentLimits.defaults().withMaxModelCalls(3), List.of(loop), a -> Decision.ALLOW)) {
            var steps = new Step[10];
            for (var i = 0; i < steps.length; i++) steps[i] = call("c" + i, "t", "{}");
            var llm = new Scripted(steps);
            rig.model("m", llm);
            var completion = rig.await(rig.spawn("worker", "m"));
            assertEquals(Completion.Outcome.FAILED, completion.outcome());
            assertTrue(completion.reason().toLowerCase().contains("limit"), completion.reason());
            assertEquals(3, llm.calls());
        }
    }

    @Test
    void limitedClientStopsAtTheLimit() {
        var ran = new java.util.concurrent.atomic.AtomicInteger();
        var inner = new Scripted(text("a"), text("b"));
        var limited = new LimitedLlm(inner, 1, ran::incrementAndGet);
        var request = new org.github.lscoughlin.jeorje.core.model.LlmRequest("s", List.of(), List.of(), 10);
        var events = new java.util.ArrayList<Object>();
        for (var i = 0; i < 2; i++) {
            limited.stream(request, org.github.lscoughlin.jeorje.core.model.CancellationToken.none())
                .subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                    @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(10); }
                    @Override public void onNext(org.github.lscoughlin.jeorje.core.model.LlmEvent e) { events.add(e); }
                    @Override public void onError(Throwable t) { events.add(t); }
                    @Override public void onComplete() { }
                });
        }
        assertEquals(1, inner.calls());
        assertEquals(1, ran.get());
        assertEquals(2, events.size(), "only the first call produced events");
    }

    @Test
    void timeLimitFailsTheSubagent() throws Exception {
        try (var rig = rig(SubagentLimits.defaults().withMaxDuration(Duration.ofMillis(200)), List.of(),
            a -> Decision.ALLOW)) {
            rig.model("m", new Scripted(hang(new CountDownLatch(1))));
            var completion = rig.await(rig.spawn("worker", "m"));
            assertEquals(Completion.Outcome.FAILED, completion.outcome());
            assertTrue(completion.reason().contains("Time limit"), completion.reason());
            assertEquals(AgentLifecycle.FAILED, rig.runtime.info(completion.agent()).orElseThrow().state());
        }
    }

    // ---- tools and policy ----

    @Test
    void restrictedRegistryAndNarrowedPolicy() throws Exception {
        var parentPolicy = (org.github.lscoughlin.jeorje.core.port.PermissionPolicy) a -> switch (a.tool()) {
            case "w" -> Decision.ASK;
            case "x" -> Decision.DENY;
            default -> Decision.ALLOW;
        };
        var tools = List.of(simple("spawn_agent"), simple("r"), simple("w"), simple("x"));
        try (var rig = rig(SubagentLimits.defaults(), tools, parentPolicy)) {
            rig.model("m", new Scripted(text(WORK_TEXT), text(VALID)));
            var id = rig.runtime.spawn(rig.root, SpawnRequest.of("worker", "Task").withModel("m")
                .withTools(List.of("r", "w", "missing")));
            var policy = rig.runtime.policyOf(id).orElseThrow();
            assertEquals(List.of("r", "w"), rig.runtime.toolNames(id));
            assertEquals(java.util.Set.of("missing"), rig.runtime.droppedTools(id));
            assertEquals(Decision.ALLOW, policy.check(use("r")));
            assertEquals(Decision.ASK, policy.check(use("w")), "not wider than the parent");
            assertEquals(Decision.DENY, policy.check(use("x")), "x is not in the tool set");
            assertEquals(Decision.DENY, policy.check(use("spawn_agent")));
            rig.await(id);
        }
    }

    @Test
    void defaultToolSetIsTheParentSetWithoutSpawn() throws Exception {
        var tools = List.of(simple("spawn_agent"), simple("r"), simple("w"));
        try (var rig = rig(SubagentLimits.defaults(), tools, a -> Decision.ALLOW)) {
            rig.runtime.addSharedTools(List.of(simple("recall")));
            rig.model("m", new Scripted(text(WORK_TEXT), text(VALID)));
            var id = rig.spawn("worker", "m");
            assertEquals(List.of("r", "w", "recall"), rig.runtime.toolNames(id));
            rig.await(id);
        }
    }

    @Test
    void aSubagentGetsSpawnOnlyWhenItAsksAndTheParentHasIt() throws Exception {
        var tools = List.of(simple("spawn_agent"), simple("r"));
        try (var rig = rig(SubagentLimits.defaults(), tools, a -> Decision.ALLOW)) {
            rig.model("m", new Scripted(text(WORK_TEXT), text(VALID)));
            var id = rig.runtime.spawn(rig.root, SpawnRequest.of("worker", "Task").withModel("m")
                .withTools(List.of("spawn_agent", "r")));
            assertEquals(List.of("spawn_agent", "r"), rig.runtime.toolNames(id));
            rig.await(id);
        }
    }

    @Test
    void narrowedPolicyAppliesTheExtraPolicyToo() {
        var narrowed = PolicyNarrowing.narrow(a -> Decision.ALLOW, a -> Decision.ASK, java.util.Set.of("r"));
        assertEquals(Decision.ASK, narrowed.check(use("r")));
        assertEquals(Decision.DENY, narrowed.check(use("z")));
        assertEquals(Decision.DENY, PolicyNarrowing.stricter(Decision.ASK, Decision.DENY));
        assertEquals(Decision.ASK, PolicyNarrowing.stricter(Decision.ALLOW, Decision.ASK));
    }

    @Test
    void spawnRefusesBadInput() throws Exception {
        try (var rig = new Rig(dir)) {
            assertThrows(SpawnException.class, () -> rig.runtime.spawn(rig.root, SpawnRequest.of(" ", "t")));
            assertThrows(SpawnException.class, () -> rig.runtime.spawn(rig.root, SpawnRequest.of("n", " ")));
            assertThrows(SpawnException.class, () -> rig.runtime.spawn(rig.root,
                SpawnRequest.of("n", "t").withType("nope")));
            assertThrows(SpawnException.class, () -> rig.runtime.spawn(rig.root,
                SpawnRequest.of("n", "t").withIsolation("bogus")));
            assertThrows(SpawnException.class, () -> rig.runtime.spawn(new org.github.lscoughlin.jeorje.core.model.AgentId("x"),
                SpawnRequest.of("n", "t")));
            assertThrows(SpawnException.class, () -> SpawnMode.parse("later"));
            assertEquals(SpawnMode.WAIT, SpawnMode.parse("wait"));
            assertEquals(SpawnMode.BACKGROUND, SpawnMode.parse(null));
        }
    }

    @Test
    void duplicateRunningNameIsRefused() throws Exception {
        try (var rig = new Rig(dir)) {
            rig.model("h", new Scripted(hang(new CountDownLatch(1))));
            rig.spawn("worker", "h");
            assertThrows(SpawnException.class, () -> rig.spawn("Worker", "h"));
        }
    }

    // ---- agent types ----

    @Test
    void agentTypeSuppliesToolsModelIsolationAndPrompt() throws Exception {
        var agents = Files.createDirectories(dir.resolve("proj/.agents/agents"));
        Files.writeString(agents.resolve("reviewer.md"), """
            ---
            name: reviewer
            description: Reviews code.
            tools: [r]
            model: review-model
            isolation: shared
            ---
            You review code. Never change files.
            """);
        var types = AgentTypeRegistry.scan(Scopes.roots("agents", dir.resolve("home"), dir.resolve("proj"), true,
            List.of(), List.of()));
        var tools = List.of(simple("r"), simple("w"));
        var settings = org.github.lscoughlin.jeorje.core.agent.AgentSettings.defaults("s1", dir, 100_000)
            .withSleeper(d -> { });
        var llm = new Scripted(text(WORK_TEXT), text(VALID));
        var seenModel = new java.util.concurrent.atomic.AtomicReference<String>();
        try (var runtime = SubagentRuntime.builder("s1", settings, model -> {
            seenModel.set(model);
            return llm;
        }).types(types).build()) {
            var root = new org.github.lscoughlin.jeorje.core.model.AgentId("root");
            runtime.registerRoot(root, "root", org.github.lscoughlin.jeorje.core.model.CancellationToken.none(), tools,
                a -> Decision.ALLOW, in -> { }, () -> 1);
            var id = runtime.spawn(root, SpawnRequest.of("rev", "Review Parser.java regex").withType("reviewer"));
            assertEquals(List.of("r"), runtime.toolNames(id));
            runtime.await(id, org.github.lscoughlin.jeorje.core.model.CancellationToken.none());
            assertEquals("review-model", seenModel.get());
            assertTrue(llm.requestText(0).contains("You review code. Never change files."), llm.requestText(0));
            assertTrue(llm.requestText(0).contains("Review Parser.java regex"));
            var explicit = runtime.spawn(root, SpawnRequest.of("rev2", "Review").withType("reviewer")
                .withTools(List.of("r", "w")).withModel("other"));
            assertEquals(List.of("r", "w"), runtime.toolNames(explicit), "tools of the call beat tools of the type");
            runtime.await(explicit, org.github.lscoughlin.jeorje.core.model.CancellationToken.none());
            assertEquals("other", seenModel.get(), "the model of the call beats the model of the type");
        }
    }

    // ---- cancel ----

    @Test
    void cancelOfTheParentCancelsAllChildren() throws Exception {
        try (var rig = new Rig(dir)) {
            rig.model("h1", new Scripted(hang(new CountDownLatch(1))));
            rig.model("h2", new Scripted(hang(new CountDownLatch(1))));
            rig.model("h3", new Scripted(hang(new CountDownLatch(1))));
            var a = rig.spawn("a", "h1");
            var b = rig.spawn("b", "h2");
            var g = rig.runtime.spawn(a, SpawnRequest.of("grandchild", "Work").withModel("h3"));
            waitUntil(() -> rig.runtime.info(g).orElseThrow().state() == AgentLifecycle.RUNNING);
            rig.rootToken.cancel();
            for (var id : List.of(a, b, g)) {
                assertEquals(Completion.Outcome.CANCELLED, rig.await(id).outcome(), id.toString());
                assertEquals(AgentLifecycle.CANCELLED, rig.runtime.info(id).orElseThrow().state());
            }
        }
    }

    @Test
    void cancelOfOneSubagentKeepsItsHistoryAndSiblings() throws Exception {
        try (var rig = new Rig(dir)) {
            rig.model("h1", new Scripted(hang(new CountDownLatch(1))));
            rig.model("m", new Scripted(text(WORK_TEXT), text(VALID)));
            var a = rig.spawn("a", "h1");
            var b = rig.spawn("b", "m");
            assertTrue(rig.runtime.cancel(a.value()));
            assertEquals(Completion.Outcome.CANCELLED, rig.await(a).outcome());
            assertEquals(Completion.Outcome.COMPLETED, rig.await(b).outcome());
            assertFalse(rig.runtime.cancel("a"), "an ended agent cannot be cancelled again");
            assertFalse(rig.runtime.cancel("nobody"));
        }
    }

    @Test
    void waitModeCancelOfTheCallerTokenCancelsTheChild() throws Exception {
        try (var rig = new Rig(dir)) {
            rig.model("h", new Scripted(hang(new CountDownLatch(1))));
            var id = rig.runtime.spawn(rig.root, SpawnRequest.of("worker", "Work").withModel("h")
                .withMode(SpawnMode.WAIT));
            var turn = new org.github.lscoughlin.jeorje.core.model.CancellationToken();
            var result = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return rig.runtime.await(id, turn);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });
            turn.cancel();
            assertEquals(Completion.Outcome.CANCELLED, result.get(10, TimeUnit.SECONDS).outcome());
        }
    }

    @Test
    void steerReachesARunningSubagentAtTheNextBoundary() throws Exception {
        var release = new CountDownLatch(1);
        var slow = tool("slow", false, (in, ctx) -> {
            release.await(10, TimeUnit.SECONDS);
            return org.github.lscoughlin.jeorje.core.model.ToolResultData.ok("slow done");
        });
        try (var rig = rig(SubagentLimits.defaults(), List.of(slow), a -> Decision.ALLOW)) {
            var llm = new Scripted(call("c1", "slow", "{}"), text(WORK_TEXT), text(VALID));
            rig.model("m", llm);
            var id = rig.spawn("worker", "m");
            waitUntil(() -> llm.calls() == 1);
            assertTrue(rig.runtime.steer(id, "use tabs"));
            release.countDown();
            rig.await(id);
            assertTrue(llm.requestText(1).contains("[message from the user]\nuse tabs"), llm.requestText(1));
            assertFalse(rig.runtime.steer(id, "late"));
        }
    }
}
