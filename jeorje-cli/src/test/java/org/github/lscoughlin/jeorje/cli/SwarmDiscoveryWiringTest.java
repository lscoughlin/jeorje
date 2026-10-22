package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.github.lscoughlin.jeorje.core.config.ConfigLoader;
import org.github.lscoughlin.jeorje.core.config.ConfigOptions;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.swarm.PeerState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Two bridges of one user home find each other through the rendezvous directory and share one token. */
class SwarmDiscoveryWiringTest {
    @TempDir Path dir;
    private final List<SwarmBridge> bridges = new ArrayList<>();

    @AfterEach
    void close() {
        bridges.forEach(SwarmBridge::close);
    }

    private JeorjeConfig config(Map<String, Object> flags) {
        var all = new java.util.HashMap<String, Object>(flags);
        all.put("swarm.gossipIntervalMs", 100);
        return ConfigLoader.load(new ConfigOptions(dir.resolve("home"), dir.resolve("work"), Map.of(), all, null, false))
                .config();
    }

    private SwarmBridge start(JeorjeConfig config) throws Exception {
        Files.createDirectories(dir.resolve("work"));
        var bridge = SwarmBridge.forConfig(config, dir.resolve("home"), dir.resolve("work"), text -> { }, null).start();
        bridges.add(bridge);
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

    @Test
    void twoBridgesOfOneHomeHaveDistinctIdsOneTokenAndSeeEachOther() throws Exception {
        var config = config(Map.of("swarm.ssh.enabled", false));
        var a = start(config);
        var b = start(config);
        assertNotEquals(a.peerId(), b.peerId());
        assertFalse(a.token().isBlank());
        assertEquals(a.token(), b.token());
        assertTrue(Files.isRegularFile(dir.resolve("home/.jeorje/swarm-token")));
        assertTrue(await(() -> a.peers(PeerState.ALIVE).stream().anyMatch(p -> p.peerId().equals(b.peerId()))
                && b.peers(PeerState.ALIVE).stream().anyMatch(p -> p.peerId().equals(a.peerId()))),
                "the bridges do not see each other");
    }

    @Test
    void aConfiguredTokenWinsOverTheFile() throws Exception {
        var bridge = start(config(Map.of("swarm.ssh.enabled", false, "swarm.token", "my-own-token")));
        assertEquals("my-own-token", bridge.token());
        assertFalse(Files.exists(dir.resolve("home/.jeorje/swarm-token")));
    }

    @Test
    void sshEnabledGivesAnAddressAndSshDisabledGivesNone() throws Exception {
        var on = start(config(Map.of()));
        assertFalse(on.sshAddress().isBlank());
        assertEquals(on.sshAddress(), on.node().self().sshAddress());
        var off = start(config(Map.of("swarm.ssh.enabled", false)));
        assertEquals("", off.sshAddress());
        assertEquals("", off.node().self().sshAddress());
    }

    @Test
    void theRendezvousFileIsRemovedWhenTheBridgeCloses() throws Exception {
        var bridge = start(config(Map.of("swarm.ssh.enabled", false)));
        var file = dir.resolve("home/.jeorje/swarm/rendezvous/" + bridge.peerId() + ".json");
        assertTrue(Files.isRegularFile(file));
        bridge.close();
        assertFalse(Files.exists(file));
    }
}
