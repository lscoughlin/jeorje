package org.github.lscoughlin.jeorje.swarm;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.session.ClientSession;
import org.github.lscoughlin.jeorje.core.port.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link Transport} over SSH. The send target is a peer id. A resolver maps the peer id to the
 * {@code host:port} address of the SSH server of that peer. The transport keeps one connection to each target
 * and sends framed JSON on the command channel {@value SwarmSshServer#COMMAND}.
 *
 * <p>The server side is a {@link SwarmSshServer}. Install {@link #serverHandler()} on it. The handler reads
 * frames and passes each one as an {@link Transport.Envelope} to the message handler. The peer id of an
 * incoming envelope is the SSH user name, which is the peer id of the sender.
 *
 * <p>The client uses the swarm token as the password. It accepts any host key, because host keys are ephemeral.
 */
public final class SshChannelTransport implements Transport {
    private static final Logger log = LoggerFactory.getLogger(SshChannelTransport.class);

    private final SwarmConfig config;
    private final String peerId;
    private final SshKeys keys;
    private final String token;
    private final Function<String, String> sshAddress;
    private final Map<String, Conn> outbound = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private volatile Consumer<Envelope> handler;
    private volatile boolean closed;
    private SshClient client;

    /**
     * @param config the swarm settings; the transport uses the message size and the handshake timeout
     * @param peerId the id of this peer; it is the SSH user name on every connection
     * @param keys the host key of the local SSH server
     * @param token the swarm token, which is the SSH password
     * @param sshAddress maps a peer id to the {@code host:port} of its SSH server, or to null or blank
     */
    public SshChannelTransport(SwarmConfig config, String peerId, SshKeys keys, String token,
                               Function<String, String> sshAddress) {
        this.config = config;
        this.peerId = peerId;
        this.keys = keys;
        this.token = token;
        this.sshAddress = sshAddress;
    }

    /** The host key of the local SSH server. */
    public SshKeys keys() {
        return keys;
    }

    @Override
    public void onMessage(Consumer<Envelope> handler) {
        this.handler = handler;
    }

    /** The handler for the {@link SwarmSshServer} of this peer. It delivers the frames to the message handler. */
    public SwarmSshServer.ChannelHandler serverHandler() {
        return (user, in, out) -> {
            var buffered = new BufferedInputStream(in);
            try {
                while (!closed) {
                    var frame = SshFrames.read(buffered, config.maxMessageBytes());
                    var current = handler;
                    if (current == null) {
                        log.atDebug().log(() -> "SSH message dropped: no handler");
                        continue;
                    }
                    try {
                        current.accept(new Envelope(user, new String(frame, StandardCharsets.UTF_8)));
                    } catch (RuntimeException e) {
                        log.atWarn().log(() -> "SSH message handler failed: " + e);
                    }
                }
            } catch (EOFException e) {
                // The peer closed the channel.
            }
        };
    }

    @Override
    public void send(String target, String body) {
        if (closed) {
            throw new IllegalStateException("transport is closed");
        }
        var address = sshAddress.apply(target);
        if (address == null || address.isBlank()) {
            throw new UncheckedIOException(new IOException("peer " + target + " has no SSH address"));
        }
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > config.maxMessageBytes()) {
            throw new IllegalArgumentException(
                    "message of " + bytes.length + " bytes is above " + config.maxMessageBytes());
        }
        IOException last = null;
        for (var attempt = 0; attempt < 2; attempt++) {
            try {
                connection(target, address).write(bytes);
                return;
            } catch (IOException e) {
                last = e;
                var stale = outbound.remove(target);
                if (stale != null) {
                    stale.close();
                }
            }
        }
        var failure = last;
        log.atWarn().log(() -> "cannot send to " + target + " over SSH: " + failure.getMessage());
        throw new UncheckedIOException("cannot send to " + target, failure);
    }

    private Conn connection(String target, String address) throws IOException {
        var existing = outbound.get(target);
        if (existing != null && existing.address.equals(address) && existing.open()) {
            return existing;
        }
        var lock = locks.computeIfAbsent(target, k -> new Object());
        synchronized (lock) {
            existing = outbound.get(target);
            if (existing != null && existing.address.equals(address) && existing.open()) {
                return existing;
            }
            if (existing != null) {
                existing.close();
                outbound.remove(target);
            }
            var conn = connect(address);
            outbound.put(target, conn);
            return conn;
        }
    }

    private synchronized SshClient client() {
        if (closed) {
            throw new IllegalStateException("transport is closed");
        }
        if (client == null) {
            client = SshClients.start();
        }
        return client;
    }

    private Conn connect(String address) throws IOException {
        var timeout = Duration.ofMillis(Math.max(1000, config.handshakeTimeoutMs()));
        var session = SshClients.session(client(), peerId, address, token, timeout);
        try {
            var channel = SshClients.channel(session, timeout);
            drain(channel.getInvertedOut());
            return new Conn(address, session, channel, channel.getInvertedIn());
        } catch (IOException | RuntimeException e) {
            session.close(true);
            throw e;
        }
    }

    /** The server never writes on a send channel. This thread reads to the end so that no window fills up. */
    private static void drain(InputStream in) {
        Thread.ofVirtual().name("swarm-ssh-drain").start(() -> {
            try (in) {
                in.transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {
                // The channel is closed.
            }
        });
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        outbound.values().forEach(Conn::close);
        outbound.clear();
        SshClient current;
        synchronized (this) {
            current = client;
            client = null;
        }
        if (current != null) {
            current.stop();
        }
    }

    private final class Conn {
        private final String address;
        private final ClientSession session;
        private final ChannelExec channel;
        private final OutputStream out;

        Conn(String address, ClientSession session, ChannelExec channel, OutputStream out) {
            this.address = address;
            this.session = session;
            this.channel = channel;
            this.out = out;
        }

        boolean open() {
            return session.isOpen() && channel.isOpen();
        }

        synchronized void write(byte[] bytes) throws IOException {
            SshFrames.write(out, bytes, config.maxMessageBytes());
        }

        void close() {
            session.close(true);
        }
    }
}
