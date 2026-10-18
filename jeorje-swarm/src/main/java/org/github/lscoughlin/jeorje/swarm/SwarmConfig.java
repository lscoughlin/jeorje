package org.github.lscoughlin.jeorje.swarm;

import java.util.List;

/**
 * The swarm settings. The names follow the {@code swarm} section of the config.
 *
 * @param bind the {@code host:port} bind address
 * @param wka the well-known addresses
 * @param token the shared token; empty means no token (loopback only)
 * @param gossipIntervalMs the length of one gossip round
 * @param pingTimeoutMs the wait for a direct ack, and again for an indirect ack
 * @param indirectPings the number of peers that send an indirect ping
 * @param suspectTimeoutMs the time before a suspect peer becomes dead
 * @param deadRetentionMs the time that dead and left records stay in the table
 * @param maxPeers the maximum swarm size
 * @param maxMessageBytes the maximum message size
 * @param handshakeTimeoutMs the time limit of the handshake
 * @param requestTimeoutMs the default time limit of a remote request
 */
public record SwarmConfig(
        String bind,
        List<String> wka,
        String token,
        long gossipIntervalMs,
        long pingTimeoutMs,
        int indirectPings,
        long suspectTimeoutMs,
        long deadRetentionMs,
        int maxPeers,
        int maxMessageBytes,
        long handshakeTimeoutMs,
        long requestTimeoutMs) {

    public SwarmConfig {
        wka = wka == null ? List.of() : List.copyOf(wka);
        token = token == null ? "" : token;
    }

    public static SwarmConfig defaults() {
        return new SwarmConfig("127.0.0.1:7600", List.of(), "", 1000, 500, 3, 10_000, 5 * 60_000L, 64,
                1024 * 1024, 5000, 30_000);
    }

    public SwarmConfig withBind(String v) {
        return new SwarmConfig(v, wka, token, gossipIntervalMs, pingTimeoutMs, indirectPings, suspectTimeoutMs,
                deadRetentionMs, maxPeers, maxMessageBytes, handshakeTimeoutMs, requestTimeoutMs);
    }

    public SwarmConfig withWka(List<String> v) {
        return new SwarmConfig(bind, v, token, gossipIntervalMs, pingTimeoutMs, indirectPings, suspectTimeoutMs,
                deadRetentionMs, maxPeers, maxMessageBytes, handshakeTimeoutMs, requestTimeoutMs);
    }

    public SwarmConfig withToken(String v) {
        return new SwarmConfig(bind, wka, v, gossipIntervalMs, pingTimeoutMs, indirectPings, suspectTimeoutMs,
                deadRetentionMs, maxPeers, maxMessageBytes, handshakeTimeoutMs, requestTimeoutMs);
    }

    public SwarmConfig withGossipIntervalMs(long v) {
        return new SwarmConfig(bind, wka, token, v, pingTimeoutMs, indirectPings, suspectTimeoutMs,
                deadRetentionMs, maxPeers, maxMessageBytes, handshakeTimeoutMs, requestTimeoutMs);
    }

    public SwarmConfig withPingTimeoutMs(long v) {
        return new SwarmConfig(bind, wka, token, gossipIntervalMs, v, indirectPings, suspectTimeoutMs,
                deadRetentionMs, maxPeers, maxMessageBytes, handshakeTimeoutMs, requestTimeoutMs);
    }

    public SwarmConfig withSuspectTimeoutMs(long v) {
        return new SwarmConfig(bind, wka, token, gossipIntervalMs, pingTimeoutMs, indirectPings, v,
                deadRetentionMs, maxPeers, maxMessageBytes, handshakeTimeoutMs, requestTimeoutMs);
    }

    public SwarmConfig withDeadRetentionMs(long v) {
        return new SwarmConfig(bind, wka, token, gossipIntervalMs, pingTimeoutMs, indirectPings, suspectTimeoutMs,
                v, maxPeers, maxMessageBytes, handshakeTimeoutMs, requestTimeoutMs);
    }

    public SwarmConfig withMaxPeers(int v) {
        return new SwarmConfig(bind, wka, token, gossipIntervalMs, pingTimeoutMs, indirectPings, suspectTimeoutMs,
                deadRetentionMs, v, maxMessageBytes, handshakeTimeoutMs, requestTimeoutMs);
    }

    public SwarmConfig withMaxMessageBytes(int v) {
        return new SwarmConfig(bind, wka, token, gossipIntervalMs, pingTimeoutMs, indirectPings, suspectTimeoutMs,
                deadRetentionMs, maxPeers, v, handshakeTimeoutMs, requestTimeoutMs);
    }

    public SwarmConfig withHandshakeTimeoutMs(long v) {
        return new SwarmConfig(bind, wka, token, gossipIntervalMs, pingTimeoutMs, indirectPings, suspectTimeoutMs,
                deadRetentionMs, maxPeers, maxMessageBytes, v, requestTimeoutMs);
    }

    public SwarmConfig withRequestTimeoutMs(long v) {
        return new SwarmConfig(bind, wka, token, gossipIntervalMs, pingTimeoutMs, indirectPings, suspectTimeoutMs,
                deadRetentionMs, maxPeers, maxMessageBytes, handshakeTimeoutMs, v);
    }
}
