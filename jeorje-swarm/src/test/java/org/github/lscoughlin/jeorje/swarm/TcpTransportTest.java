package org.github.lscoughlin.jeorje.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.github.lscoughlin.jeorje.core.port.Transport.Envelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TcpTransportTest {
    private static SwarmConfig config(String token) {
        return SwarmConfig.defaults().withBind("127.0.0.1:0").withToken(token).withHandshakeTimeoutMs(500)
                .withGossipIntervalMs(30).withPingTimeoutMs(60).withSuspectTimeoutMs(600);
    }

    private static BlockingQueue<Envelope> inbox(TcpTransport transport) {
        var queue = new LinkedBlockingQueue<Envelope>();
        transport.onMessage(queue::add);
        return queue;
    }

    @Test
    void framesCarryJsonBetweenPeersAndTheSenderIsTheHandshakePeer() throws Exception {
        try (var a = new TcpTransport(config("t"), "peer-a", null);
                var b = new TcpTransport(config("t"), "peer-b", null)) {
            var received = inbox(b);
            a.send(b.address(), "{\"hello\":1}");
            a.send(b.address(), "{\"hello\":2}");
            var first = received.poll(5, TimeUnit.SECONDS);
            assertEquals("peer-a", first.peerId());
            assertEquals("{\"hello\":1}", first.body());
            assertEquals("{\"hello\":2}", received.poll(5, TimeUnit.SECONDS).body());
        }
    }

    @Test
    void aWrongTokenFailsTheHandshake() throws Exception {
        try (var a = new TcpTransport(config("one"), "peer-a", null);
                var b = new TcpTransport(config("two"), "peer-b", null)) {
            var received = inbox(b);
            var error = assertThrows(UncheckedIOException.class, () -> a.send(b.address(), "{}"));
            assertTrue(error.getCause().getMessage().contains("handshake"), error.getCause().getMessage());
            assertEquals(null, received.poll(300, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void theTokenNeverGoesOverTheNetwork() throws Exception {
        var token = "super-secret-token-value";
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
                var a = new TcpTransport(config(token), "peer-a", null)) {
            var captured = new java.io.ByteArrayOutputStream();
            var thread = Thread.ofVirtual().start(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(1000);
                    socket.getInputStream().transferTo(captured);
                } catch (IOException e) {
                    // The timeout ends the capture.
                }
            });
            assertThrows(UncheckedIOException.class, () -> a.send("127.0.0.1:" + server.getLocalPort(), "{}"));
            thread.join();
            var text = captured.toString(StandardCharsets.ISO_8859_1);
            assertTrue(text.contains("hello"));
            assertTrue(!text.contains(token));
        }
    }

    @Test
    void withoutATokenTheSwarmPortMustBeLoopback() {
        var open = SwarmConfig.defaults().withBind("0.0.0.0:0");
        assertThrows(IllegalArgumentException.class, () -> new TcpTransport(open, "p", null));
        try (var ok = new TcpTransport(SwarmConfig.defaults().withBind("0.0.0.0:0").withToken("t"), "p", null)) {
            assertTrue(ok.address().startsWith("0.0.0.0:"));
        }
    }

    @Test
    void aBigMessageIsRefused() throws Exception {
        var small = config("t").withMaxMessageBytes(1000);
        try (var a = new TcpTransport(small, "peer-a", null); var b = new TcpTransport(small, "peer-b", null)) {
            var received = inbox(b);
            assertThrows(IllegalArgumentException.class, () -> a.send(b.address(), "x".repeat(2000)));
            // A raw peer that sends a frame above the limit gets disconnected.
            try (var raw = rawHandshake(b)) {
                raw.out.writeInt(5000);
                raw.out.flush();
                raw.socket.setSoTimeout(2000);
                assertEquals(-1, raw.socket.getInputStream().read());
            }
            assertEquals(null, received.poll(200, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void aConnectionThatSendsNoHandshakeIsDroppedAfterTheTimeout() throws Exception {
        try (var b = new TcpTransport(config("t").withHandshakeTimeoutMs(300), "peer-b", null)) {
            var hostPort = TcpTransport.splitAddress(b.address());
            try (var socket = new Socket(hostPort.host(), hostPort.port())) {
                socket.setSoTimeout(3000);
                var start = System.nanoTime();
                assertEquals(-1, socket.getInputStream().read());
                var millis = (System.nanoTime() - start) / 1_000_000;
                assertTrue(millis < 2500, "dropped after " + millis + " ms");
            }
        }
    }

    @Test
    void aWrongProtocolVersionIsRejected() throws Exception {
        try (var b = new TcpTransport(config("t"), "peer-b", null)) {
            var hostPort = TcpTransport.splitAddress(b.address());
            try (var socket = new Socket(hostPort.host(), hostPort.port())) {
                var out = new DataOutputStream(socket.getOutputStream());
                var in = new DataInputStream(socket.getInputStream());
                var hello = "{\"t\":\"hello\",\"v\":99,\"peerId\":\"x\",\"address\":\"a:1\",\"nonce\":\"n\"}"
                        .getBytes(StandardCharsets.UTF_8);
                out.writeInt(hello.length);
                out.write(hello);
                out.flush();
                var reply = new String(in.readNBytes(in.readInt()), StandardCharsets.UTF_8);
                assertTrue(reply.contains("reject"), reply);
                assertTrue(reply.contains("protocol version 99"), reply);
            }
        }
    }

    private record Raw(Socket socket, DataOutputStream out) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** Completes a real client handshake with the server and returns the open socket. */
    private static Raw rawHandshake(TcpTransport server) throws IOException {
        var hostPort = TcpTransport.splitAddress(server.address());
        var socket = new Socket(hostPort.host(), hostPort.port());
        var out = new DataOutputStream(socket.getOutputStream());
        new Handshake("t", "raw", "127.0.0.1:1").client(new DataInputStream(socket.getInputStream()), out);
        return new Raw(socket, out);
    }

    @Test
    void framingRoundTripAndLimit() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        Framing.write(new DataOutputStream(bytes), "{\"a\":1}".getBytes(StandardCharsets.UTF_8), 100);
        var in = new DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()));
        assertEquals("{\"a\":1}", new String(Framing.read(in, 100), StandardCharsets.UTF_8));
        assertThrows(EOFException.class, () -> Framing.read(in, 100));
        assertThrows(Framing.FrameTooLargeException.class,
                () -> Framing.write(new DataOutputStream(new java.io.ByteArrayOutputStream()), new byte[200], 100));
        var big = new DataInputStream(new java.io.ByteArrayInputStream(new byte[] {0, 0, 1, 0}));
        assertThrows(Framing.FrameTooLargeException.class, () -> Framing.read(big, 100));
    }

    @Test
    void sendToADeadAddressFails() {
        try (var a = new TcpTransport(config("t"), "peer-a", null)) {
            assertThrows(UncheckedIOException.class, () -> a.send("127.0.0.1:1", "{}"));
        }
    }

    @Test
    void aReconnectWorksAfterTheRemoteRestarts() throws Exception {
        try (var a = new TcpTransport(config("t"), "peer-a", null)) {
            var b = new TcpTransport(config("t"), "peer-b", null);
            var received = inbox(b);
            a.send(b.address(), "{\"n\":1}");
            assertEquals("{\"n\":1}", received.poll(5, TimeUnit.SECONDS).body());
            var port = TcpTransport.splitAddress(b.address()).port();
            b.close();
            try (var c = new TcpTransport(config("t").withBind("127.0.0.1:" + port), "peer-b", null)) {
                var again = inbox(c);
                Cluster.await(() -> {
                    try {
                        a.send(c.address(), "{\"n\":2}");
                        return !again.isEmpty();
                    } catch (RuntimeException e) {
                        return false;
                    }
                }, 5000);
                assertEquals("{\"n\":2}", again.poll(2, TimeUnit.SECONDS).body());
            }
        }
    }

    @Test
    void tlsEncryptsTheConnection(@TempDir Path dir) throws Exception {
        var store = dir.resolve("swarm.p12");
        var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process;
        try {
            process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "swarm", "-keyalg", "RSA", "-keysize", "2048",
                    "-dname", "CN=localhost", "-validity", "2", "-storetype", "PKCS12", "-keystore", store.toString(),
                    "-storepass", "changeit").redirectErrorStream(true).start();
        } catch (IOException e) {
            assumeTrue(false, "keytool is not available");
            return;
        }
        process.getInputStream().readAllBytes();
        assumeTrue(process.waitFor() == 0, "keytool failed");
        SSLContext tls = TlsSupport.fromKeyStore(store, "changeit".toCharArray());
        try (var a = new TcpTransport(config("t"), "peer-a", tls); var b = new TcpTransport(config("t"), "peer-b", tls)) {
            var received = inbox(b);
            a.send(b.address(), "{\"secure\":true}");
            assertEquals("{\"secure\":true}", received.poll(5, TimeUnit.SECONDS).body());
        }
        // A plain client cannot talk to a TLS server.
        try (var b = new TcpTransport(config("t").withHandshakeTimeoutMs(500), "peer-b", tls);
                var plain = new TcpTransport(config("t").withHandshakeTimeoutMs(500), "peer-p", null)) {
            assertThrows(UncheckedIOException.class, () -> plain.send(b.address(), "{}"));
        }
    }

    @Test
    void threeSwarmNodesJoinOverRealTcp() throws Exception {
        var nodes = new java.util.ArrayList<SwarmNode>();
        try {
            String firstAddress = null;
            for (var i = 0; i < 3; i++) {
                var id = "tcp" + i;
                var cfg = config("tok");
                if (firstAddress != null) {
                    cfg = cfg.withWka(List.of(firstAddress));
                }
                var transport = new TcpTransport(cfg, id, null);
                if (firstAddress == null) {
                    firstAddress = transport.address();
                }
                var self = PeerRecord.of(id, transport.address(), id);
                var node = new SwarmNode(cfg, self, transport, Clock.systemUTC(), new SwarmHandlers() {}, null);
                nodes.add(node.start());
            }
            assertTrue(Cluster.await(() -> Cluster.allAlive(nodes), 15_000), "tcp swarm converges");
            nodes.get(2).kill();
            assertTrue(Cluster.await(() -> Cluster.sees(nodes.get(0), PeerState.DEAD, "tcp2")
                    && Cluster.sees(nodes.get(1), PeerState.DEAD, "tcp2"), 15_000), "tcp failure detection");
        } finally {
            nodes.forEach(SwarmNode::kill);
        }
    }

    @Test
    void aSwarmNodeWithAnotherTokenCannotJoinOverTcp() throws Exception {
        var good = new TcpTransport(config("tok"), "good", null);
        var goodNode = new SwarmNode(config("tok"), PeerRecord.of("good", good.address(), "good"), good,
                Clock.systemUTC(), new SwarmHandlers() {}, null).start();
        var badConfig = config("other").withWka(List.of(good.address()));
        var bad = new TcpTransport(badConfig, "bad", null);
        var badNode = new SwarmNode(badConfig, PeerRecord.of("bad", bad.address(), "bad"), bad,
                Clock.systemUTC(), new SwarmHandlers() {}, null).start();
        try {
            Cluster.await(() -> false, 600);
            assertEquals(1, goodNode.members().size());
            assertEquals(1, badNode.members().size());
        } finally {
            goodNode.kill();
            badNode.kill();
        }
    }
}
