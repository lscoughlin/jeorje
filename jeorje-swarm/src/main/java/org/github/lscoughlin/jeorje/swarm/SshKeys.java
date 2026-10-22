package org.github.lscoughlin.jeorje.swarm;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import org.apache.sshd.common.keyprovider.KeyPairProvider;

/**
 * The SSH host key of one run. The key pair lives in memory only. Jeorje never writes it to a file and never
 * pins it, so every run has a new host key. Trust comes from the swarm token, not from the key.
 */
public final class SshKeys {
    /** The size of the RSA host key in bits. */
    public static final int RSA_BITS = 3072;

    private final KeyPair pair;

    private SshKeys(KeyPair pair) {
        this.pair = pair;
    }

    /** Makes a new RSA key pair in memory. It touches no file. */
    public static SshKeys ephemeral() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_BITS);
            return new SshKeys(generator.generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot make an SSH host key", e);
        }
    }

    /** The key pair. */
    public KeyPair pair() {
        return pair;
    }

    /** The key pair as the host key provider of an SSH server. */
    public KeyPairProvider hostKeyProvider() {
        return KeyPairProvider.wrap(pair);
    }
}
