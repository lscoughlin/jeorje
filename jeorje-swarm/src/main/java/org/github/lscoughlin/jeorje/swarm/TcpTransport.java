package org.github.lscoughlin.jeorje.swarm;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import org.github.lscoughlin.jeorje.core.port.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link Transport} over TCP. Each frame is a length prefix and one JSON object. A new connection starts
 * with the handshake. The send target is the {@code host:port} address of the peer.
 *
 * <p>Without a token the transport binds to loopback only. With an {@link SSLContext} the transport uses TLS.
 */
public final class TcpTransport implements Transport {
    private static final Logger log = LoggerFactory.getLogger(TcpTransport.class);

    private final SwarmConfig config;
    private final String peerId;
    private final SSLContext tls;
    private final ServerSocket server;
    private final String address;
    private final Handshake handshake;
    private final Map<String, Conn> outbound = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Set<Socket> inbound = ConcurrentHashMap.newKeySet();
    private volatile Consumer<Envelope> handler;
    private volatile boolean closed;

    /** Binds the server socket now and starts to accept connections. */
    public TcpTransport(SwarmConfig config, String peerId, SSLContext tls) {
        this(config, peerId, tls, null);
    }

    /**
     * Binds the server socket now and starts to accept connections.
     *
     * @param advertiseHost the host that other peers use, or null to use the bind host
     */
    public TcpTransport(SwarmConfig config, String peerId, SSLContext tls, String advertiseHost) {
        this.config = config;
        this.peerId = peerId;
        this.tls = tls;
        var bind = splitAddress(config.bind());
        try {
            var bindAddress = InetAddress.getByName(bind.host());
            if (config.token().isEmpty() && !bindAddress.isLoopbackAddress()) {
                throw new IllegalArgumentException(
                        "swarm.bind " + config.bind() + " is not loopback and there is no swarm token");
            }
            server = tls == null
                    ? new ServerSocket()
                    : tls.getServerSocketFactory().createServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(bindAddress, bind.port()));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot bind " + config.bind(), e);
        }
        var host = advertiseHost != null ? advertiseHost : bind.host();
        this.address = host + ":" + server.getLocalPort();
        this.handshake = new Handshake(config.token(), peerId, address);
        Thread.ofPlatform().daemon().name("swarm-accept-" + server.getLocalPort()).start(this::acceptLoop);
    }

    record HostPort(String host, int port) {}

    static HostPort splitAddress(String address) {
        var index = address.lastIndexOf(':');
        if (index <= 0) {
            throw new IllegalArgumentException("not a host:port address: " + address);
        }
        return new HostPort(address.substring(0, index), Integer.parseInt(address.substring(index + 1)));
    }

    /** The {@code host:port} address that other peers use. */
    public String address() {
        return address;
    }

    @Override
    public void onMessage(Consumer<Envelope> handler) {
        this.handler = handler;
    }

    @Override
    public void send(String target, String body) {
        if (closed) {
            throw new IllegalStateException("transport is closed");
        }
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > config.maxMessageBytes()) {
            throw new IllegalArgumentException(
                    "message of " + bytes.length + " bytes is above " + config.maxMessageBytes());
        }
        IOException last = null;
        for (var attempt = 0; attempt < 2; attempt++) {
            try {
                var conn = connection(target);
                conn.write(bytes);
                return;
            } catch (IOException e) {
                last = e;
                var stale = outbound.remove(target);
                if (stale != null) {
                    stale.close();
                }
            }
        }
        throw new UncheckedIOException("cannot send to " + target, last);
    }

    private Conn connection(String target) throws IOException {
        var existing = outbound.get(target);
        if (existing != null) {
            return existing;
        }
        var lock = locks.computeIfAbsent(target, k -> new Object());
        synchronized (lock) {
            existing = outbound.get(target);
            if (existing != null) {
                return existing;
            }
            var conn = connect(target);
            outbound.put(target, conn);
            return conn;
        }
    }

    private Conn connect(String target) throws IOException {
        var hostPort = splitAddress(target);
        var socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(hostPort.host(), hostPort.port()), (int) config.handshakeTimeoutMs());
            Socket active = socket;
            if (tls != null) {
                active = tls.getSocketFactory().createSocket(socket, hostPort.host(), hostPort.port(), true);
                ((SSLSocket) active).setUseClientMode(true);
            }
            active.setSoTimeout((int) config.handshakeTimeoutMs());
            active.setTcpNoDelay(true);
            var in = new DataInputStream(new BufferedInputStream(active.getInputStream()));
            var out = new DataOutputStream(new BufferedOutputStream(active.getOutputStream()));
            handshake.client(in, out);
            active.setSoTimeout(0);
            return new Conn(active, out);
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Nothing to do.
            }
            throw e instanceof IOException io ? io : new IOException(e);
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                var socket = server.accept();
                inbound.add(socket);
                Thread.ofVirtual().start(() -> serve(socket));
            } catch (IOException e) {
                if (!closed) {
                    log.atDebug().log(() -> "accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout((int) config.handshakeTimeoutMs());
            socket.setTcpNoDelay(true);
            var in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            var out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            var remote = handshake.server(in, out);
            socket.setSoTimeout(0);
            while (!closed) {
                var frame = Framing.read(in, config.maxMessageBytes());
                var current = handler;
                if (current == null) {
                    log.atDebug().log(() -> "message dropped: no handler");
                    continue;
                }
                try {
                    current.accept(new Envelope(remote.peerId(), new String(frame, StandardCharsets.UTF_8)));
                } catch (RuntimeException e) {
                    log.atWarn().log(() -> "handler failed: " + e);
                }
            }
        } catch (EOFException e) {
            // The peer closed the connection.
        } catch (IOException e) {
            if (!closed) {
                log.atDebug().log(() -> "connection closed: " + e.getMessage());
            }
        } finally {
            inbound.remove(socket);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // Closing.
        }
        outbound.values().forEach(Conn::close);
        outbound.clear();
        for (var socket : inbound) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing.
            }
        }
    }

    /** The peer id of this transport. */
    public String peerId() {
        return peerId;
    }

    private final class Conn {
        private final Socket socket;
        private final DataOutputStream out;

        Conn(Socket socket, DataOutputStream out) {
            this.socket = socket;
            this.out = out;
        }

        synchronized void write(byte[] bytes) throws IOException {
            Framing.write(out, bytes, config.maxMessageBytes());
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing.
            }
        }
    }
}
