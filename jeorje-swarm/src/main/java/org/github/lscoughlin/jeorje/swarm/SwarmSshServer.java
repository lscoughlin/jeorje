package org.github.lscoughlin.jeorje.swarm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SSH server of the swarm. It carries agent traffic: messages, questions, and foreground attach.
 *
 * <p>The only trust check is a password that equals the swarm token. The host key is ephemeral. The server
 * runs one command, {@value #COMMAND}. The command hands its streams to a {@link ChannelHandler}.
 */
public final class SwarmSshServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SwarmSshServer.class);

    /** The only command that the server accepts. */
    public static final String COMMAND = "jeorje-swarm";

    /** Serves one accepted command channel. The method returns when the channel is done. */
    @FunctionalInterface
    public interface ChannelHandler {
        /**
         * @param user the user name that the client presented; the swarm uses the peer id of the client
         * @param in the bytes from the client
         * @param out the bytes to the client
         */
        void handle(String user, InputStream in, OutputStream out) throws IOException;
    }

    private final String bind;
    private final String advertiseHost;
    private final byte[] token;
    private final SshKeys keys;
    private volatile ChannelHandler handler = (user, in, out) -> { };
    private SshServer server;
    private String address;

    /**
     * @param bind the {@code host:port} bind address; port 0 takes a free port
     * @param advertiseHost the host that peers dial, or blank to use the host of the bind address
     * @param token the swarm token; it must not be empty
     */
    public SwarmSshServer(String bind, String advertiseHost, String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("the SSH server needs a non-empty swarm token");
        }
        this.bind = bind == null || bind.isBlank() ? "127.0.0.1:0" : bind;
        this.advertiseHost = advertiseHost == null ? "" : advertiseHost;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.keys = SshKeys.ephemeral();
    }

    /** Sets the handler of command channels. Set it before {@link #start()}. */
    public SwarmSshServer handler(ChannelHandler value) {
        this.handler = value;
        return this;
    }

    /** Starts to listen and returns this server. */
    public synchronized SwarmSshServer start() {
        if (server != null) {
            return this;
        }
        var split = TcpTransport.splitAddress(bind);
        var next = SshServer.setUpDefaultServer();
        next.setIoServiceFactoryFactory(new Nio2ServiceFactoryFactory());
        next.setHost(split.host());
        next.setPort(split.port());
        next.setKeyPairProvider(keys.hostKeyProvider());
        next.setPasswordAuthenticator((user, password, session) -> accepts(password));
        next.setCommandFactory((channel, command) -> new SwarmCommand(COMMAND.equals(command)));
        try {
            next.start();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start the SSH server on " + bind, e);
        }
        server = next;
        var host = advertiseHost.isBlank() ? split.host() : advertiseHost;
        address = host + ":" + next.getPort();
        return this;
    }

    private boolean accepts(String password) {
        if (password == null || password.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8), token);
    }

    /** The {@code host:port} address that peers dial. The server must be started. */
    public synchronized String address() {
        if (address == null) {
            throw new IllegalStateException("the SSH server is not started");
        }
        return address;
    }

    /** The host key of this run. */
    public SshKeys keys() {
        return keys;
    }

    @Override
    public synchronized void close() {
        if (server == null) {
            return;
        }
        try {
            server.stop(true);
        } catch (IOException | RuntimeException e) {
            log.atDebug().log(() -> "cannot stop the SSH server: " + e.getMessage());
        } finally {
            server = null;
        }
    }

    /** One command. A command other than {@value #COMMAND} ends at once with exit status 1. */
    private final class SwarmCommand implements Command {
        private InputStream in;
        private OutputStream out;
        private ExitCallback exit;
        private final AtomicBoolean done = new AtomicBoolean();
        private final boolean accepted;
        private volatile Thread worker;

        SwarmCommand(boolean accepted) {
            this.accepted = accepted;
        }

        @Override public void setInputStream(InputStream value) { this.in = value; }

        @Override public void setOutputStream(OutputStream value) { this.out = value; }

        @Override public void setErrorStream(OutputStream value) { }

        @Override public void setExitCallback(ExitCallback value) { this.exit = value; }

        @Override
        public void start(ChannelSession channel, Environment env) {
            if (!accepted) {
                log.atDebug().log(() -> "SSH command refused");
                if (exit != null) {
                    exit.onExit(1, "unknown command");
                }
                return;
            }
            var user = channel.getSession().getUsername();
            var current = handler;
            worker = Thread.ofVirtual().name("swarm-ssh-channel").start(() -> {
                try {
                    current.handle(user, in, out);
                } catch (IOException | RuntimeException e) {
                    log.atDebug().log(() -> "SSH channel of " + user + " ended: " + e.getMessage());
                } finally {
                    finish();
                }
            });
        }

        private void finish() {
            if (done.compareAndSet(false, true) && exit != null) {
                exit.onExit(0);
            }
        }

        @Override
        public void destroy(ChannelSession channel) {
            var thread = worker;
            if (thread != null) {
                thread.interrupt();
            }
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException ignored) {
                // Closing.
            }
        }
    }
}
