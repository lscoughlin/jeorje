package org.github.lscoughlin.jeorje.swarm;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;

/**
 * Makes SSH clients for the swarm. A client accepts any host key, because host keys are ephemeral and are
 * never pinned. It signs in with the password auth method only. The password is the swarm token.
 */
final class SshClients {
    private SshClients() {}

    /** Makes and starts a client. Close it with {@link SshClient#stop()}. */
    static SshClient start() {
        var client = SshClient.setUpDefaultClient();
        client.setIoServiceFactoryFactory(new Nio2ServiceFactoryFactory());
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.setUserAuthFactories(List.of(UserAuthPasswordFactory.INSTANCE));
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        client.start();
        return client;
    }

    /** Opens a signed-in session. */
    static ClientSession session(SshClient client, String user, String address, String token, Duration timeout)
            throws IOException {
        var hostPort = TcpTransport.splitAddress(address);
        var session = client.connect(user, hostPort.host(), hostPort.port()).verify(timeout).getSession();
        try {
            session.addPasswordIdentity(token);
            session.auth().verify(timeout);
            return session;
        } catch (IOException | RuntimeException e) {
            session.close(true);
            throw e;
        }
    }

    /** Opens the swarm command channel on a session. Read and write its inverted streams. */
    static ChannelExec channel(ClientSession session, Duration timeout) throws IOException {
        var channel = session.createExecChannel(SwarmSshServer.COMMAND);
        try {
            channel.open().verify(timeout);
            return channel;
        } catch (IOException | RuntimeException e) {
            channel.close(true);
            throw e;
        }
    }
}
