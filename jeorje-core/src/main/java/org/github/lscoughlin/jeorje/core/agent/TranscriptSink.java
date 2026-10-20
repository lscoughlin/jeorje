package org.github.lscoughlin.jeorje.core.agent;

import java.time.Instant;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Turn;

/** Port for the transcript of a session. The session {@code TranscriptWriter} fits this shape. */
@FunctionalInterface
public interface TranscriptSink {

    /**
     * Writes a finished turn. A sink that got messages of the turn through {@link #writeMessage} writes only the
     * messages that it does not have yet, and then the end of the turn.
     */
    void writeTurn(Turn turn, Instant timestamp);

    /**
     * Writes one message of a turn that still runs. The agent calls this for each message in order, starting
     * with the first message of the turn, when the message cannot change any more. The default does nothing.
     */
    default void writeMessage(AgentId agent, int turn, Message message, Instant timestamp) { }

    /** A sink that drops all turns. */
    static TranscriptSink none() { return (turn, timestamp) -> { }; }
}
