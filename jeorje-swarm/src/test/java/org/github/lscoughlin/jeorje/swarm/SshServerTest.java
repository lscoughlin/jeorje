package org.github.lscoughlin.jeorje.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SshServerTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void theEphemeralKeyIsRsa3072AndTouchesNoFile(@TempDir Path dir) throws IOException {
        var before = Files.list(dir).count();
        var keys = SshKeys.ephemeral();
        assertEquals("RSA", keys.pair().getPublic().getAlgorithm());
        assertEquals(3072, ((java.security.interfaces.RSAPublicKey) keys.pair().getPublic()).getModulus().bitLength());
        assertEquals(before, Files.list(dir).count());
        assertFalse(SshKeys.ephemeral().pair().getPublic().equals(keys.pair().getPublic()));
    }

    @Test
    void theServerNeedsANonEmptyToken() {
        assertThrows(IllegalArgumentException.class, () -> new SwarmSshServer("127.0.0.1:0", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new SwarmSshServer("127.0.0.1:0", "", null));
    }

    @Test
    void theRightTokenOpensAChannelAndTheHandlerEchoes() throws Exception {
        var user = new AtomicReference<String>();
        try (var server = new SwarmSshServer("127.0.0.1:0", "", "s3cret-token").handler((who, in, out) -> {
            user.set(who);
            var data = in.read();
            while (data >= 0) {
                out.write(data);
                out.flush();
                data = in.read();
            }
        }).start()) {
            assertTrue(server.address().startsWith("127.0.0.1:"));
            assertFalse(server.address().endsWith(":0"));
            var client = SshClients.start();
            try (var session = SshClients.session(client, "peer-a", server.address(), "s3cret-token", TIMEOUT);
                    var channel = SshClients.channel(session, TIMEOUT)) {
                channel.getInvertedIn().write("hello".getBytes(StandardCharsets.UTF_8));
                channel.getInvertedIn().flush();
                var read = channel.getInvertedOut().readNBytes(5);
                assertEquals("hello", new String(read, StandardCharsets.UTF_8));
                assertEquals("peer-a", user.get());
            } finally {
                client.stop();
            }
        }
    }

    @Test
    void aWrongOrEmptyPasswordFails() throws Exception {
        try (var server = new SwarmSshServer("127.0.0.1:0", "", "s3cret-token").start()) {
            var client = SshClients.start();
            try {
                for (var password : new String[] {"wrong", "s3cret-token ", "S3cret-token"}) {
                    assertThrows(IOException.class,
                            () -> SshClients.session(client, "peer-a", server.address(), password, TIMEOUT).close());
                }
            } finally {
                client.stop();
            }
        }
    }

    @Test
    void anotherCommandIsRefusedAndCloseStopsTheServer() throws Exception {
        var server = new SwarmSshServer("127.0.0.1:0", "", "s3cret-token").start();
        var address = server.address();
        var client = SshClients.start();
        try (var session = SshClients.session(client, "peer-a", address, "s3cret-token", TIMEOUT)) {
            var other = session.createExecChannel("ls");
            other.open().verify(TIMEOUT);
            var events = other.waitFor(java.util.EnumSet.of(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED),
                    TIMEOUT);
            assertTrue(events.contains(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED), "refused command");
            assertTrue(other.getExitStatus() == null || other.getExitStatus() != 0);
            assertNotNull(session);
        } finally {
            client.stop();
            server.close();
        }
        var late = SshClients.start();
        try {
            assertThrows(IOException.class,
                    () -> SshClients.session(late, "peer-a", address, "s3cret-token", Duration.ofSeconds(3)));
        } finally {
            late.stop();
        }
    }
}
