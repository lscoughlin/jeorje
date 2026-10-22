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
    public static final String AGENT_ASK = "agent_ask";
    public static final String AGENT_ANSWER = "agent_answer";
    public static final String AGENT_ERROR = "agent_error";
    public static final String AGENT_ATTACH = "agent_attach";
    public static final String AGENT_ROSTER = "agent_roster";
    public static final String AGENT_LINE = "agent_line";
    public static final String AGENT_STEER = "agent_steer";
    public static final String AGENT_ANSWER_REQ = "agent_answer_req";
    public static final String AGENT_ANSWER_REPLY = "agent_answer_reply";
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

    /** A digest entry: the incarnation, state, and SSH address of one peer. */
    public record DigestEntry(String peerId, long incarnation, PeerState state, String sshAddress) {
        public DigestEntry {
            sshAddress = sshAddress == null ? "" : sshAddress;
        }
    }

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
     * The body of {@code agent_ask}: a question from an agent to an agent on a peer.
     *
     * @param requestId the id that the answer or the error repeats
     * @param toAgent the target agent on the peer, may be null
     * @param text the question
     */
    public record AskPayload(String requestId, String toAgent, String text) {}

    /** The body of {@code agent_answer}: the answer to the question with the same request id. */
    public record AnswerPayload(String requestId, String text) {}

    /** The body of {@code agent_error}: the reason why the question with the same request id has no answer. */
    public record ErrorPayload(String requestId, String reason) {}

    /**
     * The body of {@code agent_attach}. A client sends {@code open} and {@code close}. The host answers with
     * {@code opened} (with the history lines), {@code error}, or {@code closed}.
     *
     * @param attachId the id that the client chose for the attachment
     * @param kind {@code open}, {@code close}, {@code opened}, {@code error}, or {@code closed}
     * @param agentId the agent on the host
     * @param lines the history lines of an {@code opened} body
     * @param error the reason of an {@code error} body
     */
    public record AttachBody(String attachId, String kind, String agentId, List<String> lines, String error) {
        public AttachBody {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** The body of {@code agent_line}: one live line from the host. */
    public record LineBody(String attachId, String text) {}

    /** The body of {@code agent_steer}: steering text from the client. */
    public record SteerBody(String attachId, String text) {}

    /** The body of {@code agent_answer_req}: the answer of the client to a question of the agent. */
    public record AnswerReqBody(String attachId, String requestId, String text) {}

    /** The body of {@code agent_answer_reply}: the host tells if it took the answer. */
    public record AnswerReplyBody(String attachId, String requestId, boolean ok, String error) {}

    /**
     * The body of {@code agent_roster}. A {@code request} has a request id. A {@code reply} has the same id
     * and the agents.
     */
    public record RosterBody(String requestId, String kind, List<SwarmHandlers.PeerAgent> agents) {
        public RosterBody {
            agents = agents == null ? List.of() : List.copyOf(agents);
        }
    }

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
