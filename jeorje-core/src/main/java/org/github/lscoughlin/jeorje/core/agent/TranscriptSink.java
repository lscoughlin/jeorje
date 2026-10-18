package org.github.lscoughlin.jeorje.core.agent;

import java.time.Instant;
import org.github.lscoughlin.jeorje.core.model.Turn;

/** Port for the transcript of a session. The session {@code TranscriptWriter} fits this shape. */
@FunctionalInterface
public interface TranscriptSink {

    /** Writes a finished turn. */
    void writeTurn(Turn turn, Instant timestamp);

    /** A sink that drops all turns. */
    static TranscriptSink none() { return (turn, timestamp) -> { }; }
}
