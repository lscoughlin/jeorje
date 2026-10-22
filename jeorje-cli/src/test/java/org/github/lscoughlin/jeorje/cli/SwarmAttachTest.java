package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.github.lscoughlin.jeorje.core.config.ConfigLoader;
import org.github.lscoughlin.jeorje.core.config.ConfigOptions;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.swarm.PeerState;
import org.github.lscoughlin.jeorje.swarm.SwarmHandlers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A client bridge attaches to an agent of a host bridge over SSH on the loopback interface. */
class SwarmAttachTest {
    @TempDir Path dir;
    private final List<SwarmBridge> bridges = new ArrayList<>();

    @AfterEach
    void close() {
        bridges.forEach(SwarmBridge::close);
    }

    private SwarmBridge start() throws Exception {
        Files.createDirectories(dir.resolve("work"));
        var config = ConfigLoader.load(new ConfigOptions(dir.resolve("home"), dir.resolve("work"), Map.of(),
                Map.of("swarm.gossipIntervalMs", 100), null, false)).config();
        var bridge = SwarmBridge.forConfig(config, dir.resolve("home"), dir.resolve("work"), text -> { }, null);
        bridges.add(bridge.start());
        return bridge;
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        var end = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private static final class Access implements SwarmBridge.AgentAccess {
        final List<String> steered = new CopyOnWriteArrayList<>();
        final List<String> answered = new CopyOnWriteArrayList<>();

        @Override public List<SwarmHandlers.PeerAgent> roster() {
            return List.of(new SwarmHandlers.PeerAgent("main", "main", "", "IDLE"));
        }

        @Override public List<String> history(AgentId agent) { return List.of("> earlier question", "earlier answer"); }

        @Override public void steer(AgentId agent, String text) { steered.add(agent.value() + ": " + text); }

        @Override public void answer(AgentId agent, String requestId, String text) {
            answered.add(requestId + "=" + text);
        }
    }

    @Test
    void aClientReadsHistoryAndLiveLinesAndSteersAndAnswers() throws Exception {
        var client = start();
        var host = start();
        var access = new Access();
        host.agentAccess(access);
        assertTrue(await(() -> client.node().members().containsKey(host.peerId())
                && client.node().members().get(host.peerId()).state() == PeerState.ALIVE
                && !client.node().members().get(host.peerId()).sshAddress().isBlank()));

        var roster = client.node().roster(host.peerId()).get(20, TimeUnit.SECONDS);
        assertEquals(List.of("main"), roster.stream().map(SwarmHandlers.PeerAgent::agentId).toList());

        var lines = new CopyOnWriteArrayList<String>();
        var attachment = client.node().attach(host.peerId(), "main", lines::add, () -> { });
        assertEquals(List.of("> earlier question", "earlier answer"), attachment.opened().get(20, TimeUnit.SECONDS));

        var main = new AgentId("main");
        host.onAgentEvent(new AgentEvent.TokenDelta(main, "hello wor"));
        host.onAgentEvent(new AgentEvent.TokenDelta(main, "ld\nsecond"));
        host.onAgentEvent(new AgentEvent.ToolStarted(main, "t1", "bash", "ls"));
        host.onAgentEvent(new AgentEvent.ToolFinished(main, "t1", ToolResultData.ok("file.txt\nother")));
        host.onAgentEvent(new AgentEvent.ConfirmationRequested(main,
                new org.github.lscoughlin.jeorje.core.model.ToolUse("req-9", "bash",
                        new tools.jackson.databind.json.JsonMapper().createObjectNode())));
        assertTrue(await(() -> lines.size() >= 5), lines.toString());
        assertEquals(List.of("hello world", "second", "[bash] ls", "  = file.txt"), lines.subList(0, 4));
        assertTrue(lines.get(4).contains("req-9"), lines.toString());

        attachment.steer("stop that");
        assertTrue(await(() -> access.steered.contains("main: stop that")));
        attachment.answer("req-9", "yes").get(20, TimeUnit.SECONDS);
        assertEquals(List.of("req-9=yes"), access.answered);

        var unknown = client.node().attach(host.peerId(), "ghost", line -> { }, () -> { });
        var error = assertThrows(ExecutionException.class, () -> unknown.opened().get(20, TimeUnit.SECONDS));
        assertTrue(error.getCause().getMessage().contains("unknown agent"));

        attachment.close();
        Thread.sleep(300);
        var count = lines.size();
        host.onAgentEvent(new AgentEvent.Error(main, "late"));
        Thread.sleep(300);
        assertEquals(count, lines.size());
    }
}
