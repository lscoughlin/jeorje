package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.github.lscoughlin.jeorje.core.compaction.CompactionSettings;
import org.github.lscoughlin.jeorje.core.compaction.ContextCompactor;
import org.github.lscoughlin.jeorje.core.compaction.Pruner;
import org.github.lscoughlin.jeorje.core.compaction.TermStats;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentCompactNowTest {
    @TempDir Path dir;

    private static String bigText() {
        var sb = new StringBuilder();
        for (var i = 0; i < 400; i++) sb.append("line ").append(i).append(" of the big output with some words\n");
        return sb.toString();
    }

    private static AgentSettings settings(Path dir) {
        var d = AgentSettings.defaults("s1", dir, 100_000);
        return new AgentSettings(d.sessionId(), dir, 100_000, 60, 8192, 1, 100, d.retry(), null, "", d.sleeper());
    }

    private static Pruner pruner() {
        return new Pruner(null, null, TermStats.UNIFORM, Pruner.Config.defaults());
    }

    private static ContextCompactor compactor(int budget) {
        return new ContextCompactor(pruner(), new CompactionSettings(budget, 50, 80, 1, 12, 20, 16_000),
            new AgentId("a1"));
    }

    private static Scripted script(int turns) {
        var steps = new ArrayList<Step>();
        for (var t = 1; t <= turns; t++) {
            steps.add(calls("c" + t, "big", "{}"));
            steps.add(text("Done " + t));
        }
        return new Scripted(steps.toArray(new Step[0]));
    }

    private Rig rig(Scripted llm, org.github.lscoughlin.jeorje.core.port.Tool tool) {
        return new Rig(llm, policy(Decision.ALLOW), settings(dir), tool);
    }

    private static org.github.lscoughlin.jeorje.core.port.Tool big() {
        var text = bigText();
        return tool("big", true, (in, ctx) -> ToolResultData.ok(text));
    }

    @Test
    void compactNowShrinksTheNextRequest() {
        var llm = script(5);
        var rig = rig(llm, big());
        rig.agent.withCompactor(compactor(60_000)).withHardCompactor(compactor(1));
        for (var t = 1; t <= 4; t++) rig.agent.runTurn("look " + t);
        assertTrue(rig.events(AgentEvent.Compacted.class).isEmpty(), "the normal policy did not run");
        var beforeIndex = llm.requests.size();
        var last = ContextBuilder.rawEstimate(llm.requests.getLast());

        var result = rig.agent.compactNow();
        assertTrue(result.chunksPruned() > 0, result.toString());
        assertTrue(result.tokensFreed() > 0, result.toString());
        assertEquals(1, rig.events(AgentEvent.Compacted.class).size());
        assertEquals(result, rig.events(AgentEvent.Compacted.class).getFirst());

        rig.agent.runTurn("look 5");
        var next = ContextBuilder.rawEstimate(llm.requests.get(beforeIndex));
        assertTrue(next < last, "next " + next + " last " + last);
    }

    @Test
    void compactNowWithNothingToDoPublishesNothing() {
        var rig = rig(script(1), big());
        rig.agent.withHardCompactor(compactor(1));
        rig.agent.runTurn("one");
        var result = rig.agent.compactNow();
        assertEquals(0, result.chunksPruned());
        assertEquals(0, result.tokensFreed());
        assertTrue(rig.events(AgentEvent.Compacted.class).isEmpty());
        assertEquals(0, new Rig(script(1), policy(Decision.ALLOW), settings(dir)).agent.compactNow().chunksPruned(),
            "an agent without a compactor does nothing");
    }

    @Test
    void compactNowWaitsForTheRunningTurn() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var text = bigText();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var gate = tool("big", true, (in, ctx) -> {
            if (calls.incrementAndGet() == 4) {
                started.countDown();
                release.await(20, TimeUnit.SECONDS);
            }
            return ToolResultData.ok(text);
        });
        var llm = script(4);
        var rig = rig(llm, gate);
        rig.agent.withHardCompactor(compactor(1));
        for (var t = 1; t <= 3; t++) rig.agent.runTurn("look " + t);
        var turn = Thread.ofPlatform().name("turn-4").start(() -> rig.agent.runTurn("look 4"));
        assertTrue(started.await(20, TimeUnit.SECONDS));

        var pending = CompletableFuture.supplyAsync(rig.agent::compactNow);
        assertThrows(TimeoutException.class, () -> pending.get(300, TimeUnit.MILLISECONDS),
            "compactNow waits while the turn runs");
        assertTrue(rig.events(AgentEvent.Compacted.class).isEmpty());
        release.countDown();
        var result = pending.get(20, TimeUnit.SECONDS);
        turn.join();
        assertEquals(4, rig.agent.turns().size(), "the turn ended first");
        assertTrue(result.chunksPruned() > 0);
    }

    @Test
    void liveFindsAnAgentById() {
        var rig = rig(script(1), big());
        assertSame(rig.agent, Agent.live(new AgentId("a1")).orElseThrow());
        assertTrue(Agent.live(new AgentId("nobody-here")).isEmpty());
    }
}
