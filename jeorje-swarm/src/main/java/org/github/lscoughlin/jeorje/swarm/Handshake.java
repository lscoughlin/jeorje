package org.github.lscoughlin.jeorje.swarm;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The connection handshake. Both sides send the protocol version and a random challenge. Each side proves
 * that it knows the shared token with an HMAC over both challenges. The token never goes over the network.
 */
final class Handshake {
    static final int PROTOCOL_VERSION = 1;
    private static final int MAX_FRAME = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** One handshake frame. Unused fields are null. */
    record Frame(String t, int v, String peerId, String address, String nonce, String proof, String reason) {}

    /** The remote side after a good handshake. */
    record Remote(String peerId, String address) {}

    private final byte[] key;
    private final String peerId;
    private final String address;

    Handshake(String token, String peerId, String address) {
        this.key = deriveKey(token);
        this.peerId = peerId;
        this.address = address;
    }

    private static byte[] deriveKey(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(("jeorje-swarm-v1:" + token).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String newNonce() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private String proof(String role, String firstNonce, String secondNonce, String proverId) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            var text = role + "|" + firstNonce + "|" + secondNonce + "|" + proverId;
            return Base64.getEncoder().encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean same(String a, String b) {
        return a != null && b != null
                && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(DataOutputStream out, Frame frame) throws IOException {
        Framing.write(out, Json.MAPPER.writeValueAsBytes(frame), MAX_FRAME);
    }

    private static Frame receive(DataInputStream in) throws IOException {
        try {
            return Json.MAPPER.readValue(Framing.read(in, MAX_FRAME), Frame.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IOException("bad handshake frame", e);
        }
    }

    private static void rejectIfNeeded(Frame frame, String expected) throws IOException {
        if (frame.t() == null) {
            throw new IOException("handshake frame has no type");
        }
        if (frame.t().equals("reject")) {
            throw new IOException("handshake rejected: " + frame.reason());
        }
        if (!frame.t().equals(expected)) {
            throw new IOException("unexpected handshake frame: " + frame.t());
        }
    }

    /** Runs the client side. Throws when the server rejects or the proof is wrong. */
    Remote client(DataInputStream in, DataOutputStream out) throws IOException {
        var clientNonce = newNonce();
        send(out, new Frame("hello", PROTOCOL_VERSION, peerId, address, clientNonce, null, null));
        var challenge = receive(in);
        rejectIfNeeded(challenge, "challenge");
        if (challenge.v() != PROTOCOL_VERSION) {
            throw new IOException("protocol version " + challenge.v() + " is not supported");
        }
        var expected = proof("server", clientNonce, challenge.nonce(), challenge.peerId());
        if (!same(expected, challenge.proof())) {
            throw new IOException("handshake failed: the server proof is wrong");
        }
        send(out, new Frame("proof", PROTOCOL_VERSION, peerId, null, null,
                proof("client", challenge.nonce(), clientNonce, peerId), null));
        var ok = receive(in);
        rejectIfNeeded(ok, "ok");
        return new Remote(challenge.peerId(), challenge.address());
    }

    /** Runs the server side. Sends a reject frame and throws when the client fails. */
    Remote server(DataInputStream in, DataOutputStream out) throws IOException {
        var hello = receive(in);
        if (!"hello".equals(hello.t()) || hello.peerId() == null || hello.nonce() == null) {
            reject(out, "bad hello");
            throw new IOException("bad hello");
        }
        if (hello.v() != PROTOCOL_VERSION) {
            reject(out, "protocol version " + hello.v() + " is not supported");
            throw new IOException("protocol version " + hello.v() + " is not supported");
        }
        var serverNonce = newNonce();
        send(out, new Frame("challenge", PROTOCOL_VERSION, peerId, address, serverNonce,
                proof("server", hello.nonce(), serverNonce, peerId), null));
        var reply = receive(in);
        var expected = proof("client", serverNonce, hello.nonce(), hello.peerId());
        if (!"proof".equals(reply.t()) || !same(expected, reply.proof())) {
            reject(out, "bad proof");
            throw new IOException("handshake failed: the client proof is wrong");
        }
        send(out, new Frame("ok", PROTOCOL_VERSION, peerId, address, null, null, null));
        return new Remote(hello.peerId(), hello.address());
    }

    private static void reject(DataOutputStream out, String reason) {
        try {
            send(out, new Frame("reject", PROTOCOL_VERSION, null, null, null, null, reason));
        } catch (IOException e) {
            // The connection closes anyway.
        }
    }
}
