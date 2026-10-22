package org.github.lscoughlin.jeorje.swarm;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.github.lscoughlin.jeorje.testkit.swarm.InMemoryNetwork;

/** Test helper: many swarm nodes on one in-memory network with fast timers. */
final class Cluster implements AutoCloseable {
    final InMemoryNetwork network = new InMemoryNetwork();
    /** A second network that stands in for the SSH channel. Its targets are peer ids. */
    final InMemoryNetwork sshNetwork = new InMemoryNetwork();
    final List<SwarmNode> nodes = new ArrayList<>();
    SwarmConfig base = SwarmConfig.defaults()
            .withGossipIntervalMs(30)
            .withPingTimeoutMs(40)
            .withSuspectTimeoutMs(500)
            .withHandshakeTimeoutMs(500);

    static String address(String name) {
        return "mem:" + name;
    }

    SwarmNode add(String name, List<String> wkaNames) {
        return add(name, wkaNames, "", new SwarmHandlers() {});
    }

    SwarmNode add(String name, List<String> wkaNames, String token, SwarmHandlers handlers) {
        return add(name, wkaNames, token, handlers, List.of());
    }

    SwarmNode add(String name, List<String> wkaNames, String token, SwarmHandlers handlers,
            List<String> capabilities) {
        var config = base.withToken(token).withWka(wkaNames.stream().map(Cluster::address).toList());
        var transport = network.join(address(name), name, token);
        var self = PeerRecord.of(name, address(name), name).withCapabilities(capabilities);
        var ssh = new SshStandIn(sshNetwork.join("ssh:" + name, name, token));
        var node = new SwarmNode(config, self, transport, ssh, Clock.systemUTC(), handlers, null, "ssh:" + name);
        nodes.add(node);
        node.start();
        return node;
    }

    SwarmNode node(String name) {
        return nodes.stream().filter(n -> n.peerId().equals(name)).findFirst().orElseThrow();
    }

    static boolean await(BooleanSupplier condition, long timeoutMs) {
        var end = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /** True when the node sees exactly the given peers in the given state, plus itself. */
    static boolean sees(SwarmNode node, PeerState state, String... names) {
        for (var name : names) {
            var record = node.members().get(name);
            if (record == null || record.state() != state) {
                return false;
            }
        }
        return true;
    }

    static boolean allAlive(List<SwarmNode> nodes) {
        return nodes.stream().allMatch(n -> nodes.stream()
                .allMatch(o -> sees(n, PeerState.ALIVE, o.peerId())));
    }

    @Override
    public void close() {
        nodes.forEach(SwarmNode::kill);
        network.close();
        sshNetwork.close();
    }

    /** A transport whose send target is a peer id, as with the SSH transport. */
    private record SshStandIn(org.github.lscoughlin.jeorje.core.port.Transport inner)
            implements org.github.lscoughlin.jeorje.core.port.Transport {
        @Override public void send(String peerId, String body) { inner.send("ssh:" + peerId, body); }

        @Override public void onMessage(java.util.function.Consumer<Envelope> handler) { inner.onMessage(handler); }

        @Override public void close() { inner.close(); }
    }
}
