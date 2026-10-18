package org.github.lscoughlin.jeorje.swarm;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** The message envelope and the message bodies of the swarm protocol. */
public final class Messages {
    public static final String GOSSIP = "gossip";
    public static final String PING = "ping";
    public static final String ACK = "ack";
    public static final String PING_REQ = "ping-req";
    public static final String AGENT_MESSAGE = "agent_message";
    public static final String DELEGATE = "delegate";
    public static final String DELEGATE_EVENT = "delegate_event";
    public static final String HISTORY_QUERY = "history_query";

    private Messages() {}

    /**
     * One message. Each message has a protocol version, a sender id, a message id, and a time.
     *
     * @param v the protocol version
     * @param type the message type
     * @param from the sender {@code PeerId}
     * @param fromAddr the address of the sender
     * @param id the message id
     * @param time the send time in epoch milliseconds
     * @param body the body, a JSON object
     */
    public record Message(int v, String type, String from, String fromAddr, String id, long time, JsonNode body) {}

    /** A digest entry: the incarnation and state of one peer. */
    public record DigestEntry(String peerId, long incarnation, PeerState state) {}

    /**
     * The gossip body. A request has a digest. The answer has the records that differ and a want list.
     *
     * @param digest the digest of the sender, or null in an answer
     * @param records the records
     * @param want the ids of the peers for which the sender wants the full record
     */
    public record Gossip(List<DigestEntry> digest, List<PeerRecord> records, List<String> want) {}

    /** The body of {@code ping} and {@code ack}. */
    public record Probe(long seq, PeerRecord self) {}

    /** The body of {@code ping-req}: ask the receiver to ping the target and relay the ack. */
    public record PingReq(long seq, String targetId, String targetAddress) {}

    /**
     * The body of {@code agent_message}.
     *
     * @param kind {@code text}, {@code ask}, {@code answer}, {@code steer}, or {@code cancel}
     * @param toAgent the target agent on the peer, may be null
     * @param taskId the delegated task, for {@code steer}, {@code cancel}, and task answers
     * @param questionId the question id of an {@code ask}
     * @param answerTo the question id that an {@code answer} answers
     * @param text the text
     */
    public record AgentPayload(
            String kind, String toAgent, String taskId, String questionId, String answerTo, String text) {}

    /**
     * The body of {@code delegate}.
     *
     * @param taskId the task id
     * @param task the task text
     * @param allowedTools the allowed tool names
     * @param limits the limits
     */
    public record Delegate(String taskId, String task, List<String> allowedTools, Limits limits) {}

    /**
     * The limits of a delegated task.
     *
     * @param maxTurns the maximum number of turns, 0 for no limit
     * @param timeoutSeconds the time limit, 0 for no limit
     */
    public record Limits(int maxTurns, int timeoutSeconds) {}

    /** The body of {@code history_query}. */
    public record HistoryQueryBody(
            String kind, String queryId, String text, int limit, Boolean allowed, List<HistoryHitView> hits) {}

    /**
     * One hit of a remote history search.
     *
     * @param agentId the agent that owns the chunk
     * @param snippet the snippet
     * @param score the score
     */
    public record HistoryHitView(String agentId, String snippet, float score) {}
}
