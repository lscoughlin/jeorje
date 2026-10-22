package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.github.lscoughlin.jeorje.core.agent.AgentInput;
import org.github.lscoughlin.jeorje.core.config.ConfigLoader;
import org.github.lscoughlin.jeorje.core.config.ConfigOptions;
import org.github.lscoughlin.jeorje.swarm.PeerState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Two real bridges of one home talk over SSH on the loopback interface. */
class SwarmSshMessagingTest {
    @TempDir Path dir;
    private final List<SwarmBridge> bridges = new ArrayList<>();

    @AfterEach
    void close() {
        bridges.forEach(SwarmBridge::close);
    }

    private SwarmBridge start(Map<String, Object> flags, List<AgentInput> mainInput) throws Exception {
        Files.createDirectories(dir.resolve("work"));
        var all = new java.util.HashMap<String, Object>(flags);
        all.put("swarm.gossipIntervalMs", 100);
        var config = ConfigLoader.load(new ConfigOptions(dir.resolve("home"), dir.resolve("work"), Map.of(), all,
                null, false)).config();
        var bridge = SwarmBridge.forConfig(config, dir.resolve("home"), dir.resolve("work"), text -> { }, null);
        bridge.mainInput(mainInput::add);
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

    private static boolean knowsSsh(SwarmBridge from, SwarmBridge to) {
        var record = from.node().members().get(to.peerId());
        return record != null && record.state() == PeerState.ALIVE && !record.sshAddress().isBlank();
    }

    @Test
    void aMessageReachesTheAgentAndAnAskReturnsTheAnswerWithTheAddressFromMembership() throws Exception {
        var inputA = new CopyOnWriteArrayList<AgentInput>();
        var inputB = new CopyOnWriteArrayList<AgentInput>();
        var a = start(Map.of(), inputA);
        var b = start(Map.of(), inputB);
        b.answerPeersWith((from, agent, question) -> CompletableFuture.completedFuture("echo: " + question));
        assertTrue(await(() -> knowsSsh(a, b) && knowsSsh(b, a)), "the peers learn each other SSH address");
        assertEquals(b.node().self().sshAddress(), a.node().members().get(b.peerId()).sshAddress());

        a.sendMessage(b.peerId(), "main", "please look at the build").get(20, TimeUnit.SECONDS);
        assertTrue(await(() -> !inputB.isEmpty()), "the message arrives");
        assertTrue(inputB.getFirst() instanceof AgentInput.PeerMessage);
        assertTrue(inputB.getFirst().text().contains("please look at the build"));

        assertEquals("echo: 6 x 7?", a.askPeer(b.peerId(), "main", "6 x 7?").get(20, TimeUnit.SECONDS));
    }

    @Test
    void anAskWithoutADirectAnswererFailsWithTheReasonAndStillReachesTheAgent() throws Exception {
        var inputB = new CopyOnWriteArrayList<AgentInput>();
        var a = start(Map.of(), new CopyOnWriteArrayList<>());
        var b = start(Map.of(), inputB);
        assertTrue(await(() -> knowsSsh(a, b) && knowsSsh(b, a)));
        var error = assertThrows(ExecutionException.class,
                () -> a.askPeer(b.peerId(), "main", "anyone there?").get(20, TimeUnit.SECONDS));
        assertTrue(error.getCause().getMessage().contains("send_message"), error.getCause().getMessage());
        assertTrue(await(() -> !inputB.isEmpty()));
    }

    @Test
    void aPeerWithoutSshIsNotReachableAndTheFailureDoesNotThrow() throws Exception {
        var a = start(Map.of(), new CopyOnWriteArrayList<>());
        var b = start(Map.of("swarm.ssh.enabled", false), new CopyOnWriteArrayList<>());
        assertTrue(await(() -> a.node().members().containsKey(b.peerId())));
        var ask = a.askPeer(b.peerId(), "main", "hi?");
        assertThrows(ExecutionException.class, () -> ask.get(20, TimeUnit.SECONDS));
        assertTrue(a.askPeer("nobody", "main", "hi?").isCompletedExceptionally());
        assertTrue(a.sendMessage("nobody", "main", "hi").isCompletedExceptionally());
        var message = a.sendMessage(b.peerId(), "main", "hi");
        assertThrows(ExecutionException.class, () -> message.get(20, TimeUnit.SECONDS));
    }
}
