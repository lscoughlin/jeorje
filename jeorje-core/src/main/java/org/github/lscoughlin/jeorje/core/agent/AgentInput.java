package org.github.lscoughlin.jeorje.core.agent;

import java.util.Objects;
import org.github.lscoughlin.jeorje.core.model.AgentId;

/** An item in the input queue of an agent. */
public sealed interface AgentInput {

    /** The text that becomes the first message of a turn, or null for {@link Stop}. */
    String text();

    /** Input that the user typed. */
    record UserInput(String text) implements AgentInput {
        public UserInput { Objects.requireNonNull(text, "text"); }
    }

    /** A message from a peer agent. */
    record PeerMessage(AgentId from, String text) implements AgentInput {
        public PeerMessage {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(text, "text");
        }

        @Override
        public String text() { return "[message from agent " + from + "]\n" + text; }
    }

    /** The result of a subagent. */
    record SubagentResult(AgentId from, String text) implements AgentInput {
        public SubagentResult {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(text, "text");
        }

        @Override
        public String text() { return "[result of subagent " + from + "]\n" + text; }
    }

    /** Ends the agent thread. */
    record Stop() implements AgentInput {
        @Override
        public String text() { return null; }
    }
}
