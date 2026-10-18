package org.github.lscoughlin.jeorje.swarm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Builds the optional TLS context. All peers of a swarm share one key store. */
public final class TlsSupport {
    private TlsSupport() {}

    /** Loads a PKCS12 key store, and uses it for the own key and as the trust store. */
    public static SSLContext fromKeyStore(Path keyStore, char[] password) {
        try (InputStream in = Files.newInputStream(keyStore)) {
            var store = KeyStore.getInstance("PKCS12");
            store.load(in, password);
            var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, password);
            var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            var context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalArgumentException("cannot load the TLS key store " + keyStore + ": " + e.getMessage(), e);
        }
    }
}
