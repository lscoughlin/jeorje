package org.github.lscoughlin.jeorje.swarm;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.session.SessionContext;

/**
 * The SSH host key of one run. The key pair lives in memory only. Jeorje never writes it to a file and never
 * pins it, so every run has a new host key. Trust comes from the swarm token, not from the key.
 */
public final class SshKeys {
    /** The size of the RSA host key in bits. */
    public static final int RSA_BITS = 3072;

    private final CompletableFuture<KeyPair> pair;

    private SshKeys(CompletableFuture<KeyPair> pair) {
        this.pair = pair;
    }

    /** Makes a new RSA key pair in memory and returns when it is ready. It touches no file. */
    public static SshKeys ephemeral() {
        return new SshKeys(CompletableFuture.completedFuture(generate()));
    }

    /**
     * Starts to make a new RSA key pair on a daemon thread and returns at once. The key generation takes a few
     * seconds, so the start of the program does not wait for it. {@link #pair()} and the host key provider wait
     * until the key is ready. An SSH client that connects early waits for the key exchange.
     */
    public static SshKeys background() {
        var future = new CompletableFuture<KeyPair>();
        var thread = new Thread(() -> {
            try {
                future.complete(generate());
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        }, "swarm-ssh-keygen");
        thread.setDaemon(true);
        thread.start();
        return new SshKeys(future);
    }

    private static KeyPair generate() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_BITS);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot make an SSH host key", e);
        }
    }

    /** The key pair. The call waits until the key is ready. */
    public KeyPair pair() {
        return pair.join();
    }

    /** True when the key pair is ready. */
    public boolean ready() {
        return pair.isDone();
    }

    /** The key pair as the host key provider of an SSH server. The provider waits until the key is ready. */
    public KeyPairProvider hostKeyProvider() {
        return new KeyPairProvider() {
            @Override
            public Iterable<KeyPair> loadKeys(SessionContext session) {
                return List.of(pair());
            }
        };
    }
}
