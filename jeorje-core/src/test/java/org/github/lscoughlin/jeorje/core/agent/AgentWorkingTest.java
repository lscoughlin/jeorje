package org.github.lscoughlin.jeorje.core.agent;

import static org.github.lscoughlin.jeorje.core.agent.AgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code Working} events of the agent. */
class AgentWorkingTest {
    @TempDir Path dir;

    private Rig rig(Scripted llm) {
        return new Rig(llm, policy(Decision.ALLOW), Rig.settings(dir));
    }

    private static List<AgentEvent.Working> working(Rig rig) {
        return rig.events(AgentEvent.Working.class);
    }

    @Test
    void aCallStartsWithTheEstimateAndEndsWithTheProviderCounts() {
        var fragment = "x".repeat(40);
        var llm = new Scripted(withUsage(text(fragment, fragment, fragment, fragment), 1234, 77));
        var rig = rig(llm);
        rig.agent.runTurn("hi");
        var events = working(rig);
        // The fixed clock lets only the first fragment through the throttle.
        assertEquals(4, events.size(), events::toString);
        var start = events.get(0);
        assertTrue(start.active());
        assertTrue(start.tokensUp() > 0);
        assertEquals(0, start.tokensDown());
        var first = events.get(1);
        assertTrue(first.active());
        assertEquals(start.tokensUp(), first.tokensUp());
        assertEquals(10, first.tokensDown());
        var usage = events.get(2);
        assertTrue(usage.active());
        assertEquals(1234, usage.tokensUp());
        assertEquals(77, usage.tokensDown());
        var end = events.get(3);
        assertFalse(end.active());
        assertEquals(1234, end.tokensUp());
        assertEquals(77, end.tokensDown());
    }

    @Test
    void theDownCountGrowsWhileTextStreams() {
        var now = new AtomicLong();
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }

            @Override public Clock withZone(ZoneId zone) { return this; }

            @Override public Instant instant() { return Instant.ofEpochMilli(now.addAndGet(300)); }
        };
        var events = new java.util.concurrent.CopyOnWriteArrayList<AgentEvent>();
        var fragment = "y".repeat(80);
        var llm = new Scripted(text(fragment, fragment, fragment));
        var agent = new Agent(new AgentId("a1"), Rig.settings(dir), llm, List.of(), policy(Decision.ALLOW),
            new ListStore(), (turn, ts) -> { }, events::add, clock);
        agent.runTurn("hi");
        var downs = events.stream().filter(AgentEvent.Working.class::isInstance).map(AgentEvent.Working.class::cast)
            .filter(AgentEvent.Working::active).map(AgentEvent.Working::tokensDown).toList();
        assertEquals(List.of(0, 20, 40, 60), downs);
        var last = (AgentEvent.Working) events.stream().filter(AgentEvent.Working.class::isInstance)
            .reduce((a, b) -> b).orElseThrow();
        assertFalse(last.active());
        assertEquals(60, last.tokensDown());
    }

    @Test
    void aFailedCallEndsInactive() {
        var llm = new Scripted(fail(400));
        var rig = rig(llm);
        rig.agent.runTurn("hi");
        var events = working(rig);
        assertTrue(events.getFirst().active());
        assertFalse(events.getLast().active());
        assertEquals(1, events.stream().filter(e -> !e.active()).count());
    }

    @Test
    void aRetryKeepsTheStateActiveAndTheCounts() {
        var llm = new Scripted(fail(500), text("ok"));
        var rig = rig(llm);
        rig.agent.runTurn("hi");
        var events = working(rig);
        assertEquals(1, events.stream().filter(e -> !e.active()).count(), events::toString);
        assertFalse(events.getLast().active());
        var firstUp = events.getFirst().tokensUp();
        assertTrue(events.getLast().tokensUp() >= 2 * firstUp - 1, "the failed call stays in the count");
    }

    @Test
    void aCancelEndsInactive() throws Exception {
        var emitted = new CountDownLatch(1);
        var hang = new Step.HangAfter(List.of(new LlmEvent.TextDelta("part")), emitted);
        var rig = rig(new Scripted(hang));
        var thread = Thread.ofVirtual().start(() -> rig.agent.runTurn("go"));
        assertTrue(emitted.await(5, TimeUnit.SECONDS));
        rig.agent.cancel();
        thread.join(5_000);
        var events = working(rig);
        assertTrue(events.getFirst().active());
        assertFalse(events.getLast().active());
    }
}
