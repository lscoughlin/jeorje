package org.github.lscoughlin.jeorje.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.github.lscoughlin.jeorje.core.port.Transport;
import org.junit.jupiter.api.Test;

class SshChannelTransportTest {
    private static final String TOKEN = "loopback-token";

    /** One side: a server, a transport, and the envelopes that arrived. */
    private static final class Side implements AutoCloseable {
        final SwarmSshServer server;
        final SshChannelTransport transport;
        final List<Transport.Envelope> received = new CopyOnWriteArrayList<>();

        Side(String id, Function<String, String> resolver) {
            var keys = SshKeys.ephemeral();
            transport = new SshChannelTransport(SwarmConfig.defaults(), id, keys, TOKEN, resolver);
            transport.onMessage(received::add);
            server = new SwarmSshServer("127.0.0.1:0", "", TOKEN, keys).handler(transport.serverHandler()).start();
        }

        @Override
        public void close() {
            transport.close();
            server.close();
        }
    }

    @Test
    void twoPeersExchangeTwoBodiesEachWayWithTheSenderId() throws Exception {
        var addresses = new ConcurrentHashMap<String, String>();
        try (var a = new Side("a", addresses::get); var b = new Side("b", addresses::get)) {
            addresses.put("a", a.server.address());
            addresses.put("b", b.server.address());
            a.transport.send("b", "{\"n\":1}");
            a.transport.send("b", "{\"n\":2}");
            b.transport.send("a", "{\"n\":3}");
            b.transport.send("a", "{\"n\":4}");
            assertTrue(Cluster.await(() -> a.received.size() == 2 && b.received.size() == 2, 10_000));
            assertEquals(List.of(new Transport.Envelope("a", "{\"n\":1}"), new Transport.Envelope("a", "{\"n\":2}")),
                    b.received);
            assertEquals(List.of(new Transport.Envelope("b", "{\"n\":3}"), new Transport.Envelope("b", "{\"n\":4}")),
                    a.received);
        }
    }

    @Test
    void sendToAPeerWithoutAnAddressFailsAtOnce() {
        var transport = new SshChannelTransport(SwarmConfig.defaults(), "a", SshKeys.ephemeral(), TOKEN,
                Map.<String, String>of()::get);
        try {
            var e = assertThrows(UncheckedIOException.class, () -> transport.send("nobody", "{}"));
            assertTrue(e.getCause() instanceof IOException);
            var blank = new SshChannelTransport(SwarmConfig.defaults(), "a", SshKeys.ephemeral(), TOKEN, id -> " ");
            assertThrows(UncheckedIOException.class, () -> blank.send("x", "{}"));
            blank.close();
        } finally {
            transport.close();
        }
    }

    @Test
    void aWrongTokenCannotSend() throws Exception {
        try (var b = new Side("b", id -> null)) {
            var wrong = new SshChannelTransport(SwarmConfig.defaults().withHandshakeTimeoutMs(3000), "a",
                    SshKeys.ephemeral(), "other-token", id -> b.server.address());
            try {
                assertThrows(UncheckedIOException.class, () -> wrong.send("b", "{}"));
                assertTrue(b.received.isEmpty());
            } finally {
                wrong.close();
            }
        }
    }

    @Test
    void framesAboveTheLimitAreRejected() throws Exception {
        var out = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> SshFrames.write(out, new byte[20], 10));
        assertEquals(0, out.size());
        var framed = new ByteArrayOutputStream();
        SshFrames.write(framed, "abc".getBytes(), 10);
        assertEquals("abc", new String(SshFrames.read(new ByteArrayInputStream(framed.toByteArray()), 10)));
        assertThrows(IOException.class, () -> SshFrames.read(new ByteArrayInputStream(framed.toByteArray()), 2));
    }
}
