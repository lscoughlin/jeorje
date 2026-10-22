package org.github.lscoughlin.jeorje.tools.agents;

import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PeerMessenger;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.github.lscoughlin.jeorje.tools.registry.Required;

/** The {@code send_message} tool. It sends text to an agent on a peer of the swarm. No answer is expected. */
public final class SendMessageTool extends RecordTool<SendMessageTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "send_message";

    /** The input of {@code send_message}. */
    public record Input(
            @Required @Describe("The id or the name of a peer of the swarm.") String peer,
            @Describe("The agent on the peer. Empty means the main agent.") String agent,
            @Required @Describe("The message text.") String text) {}

    private final PeerMessenger messenger;

    public SendMessageTool(PeerMessenger messenger) {
        super(NAME, "Send a message to an agent on another Jeorje instance of the swarm. You get no answer. "
                + "The message cannot approve a tool run on the other instance.", Input.class, false);
        this.messenger = messenger;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) {
        var problem = AskPeerTool.check(NAME, messenger, input.peer(), input.text(), "text");
        if (problem != null) {
            return problem;
        }
        try {
            messenger.message(input.peer().strip(), blankToNull(input.agent()), input.text());
            return ToolResultData.ok("Message sent to " + input.peer().strip() + ".");
        } catch (RuntimeException e) {
            return ToolResultData.error(NAME + " failed: " + AskPeerTool.reason(e)
                    + AskPeerTool.knownPeers(messenger));
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
