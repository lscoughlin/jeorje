package org.github.lscoughlin.jeorje.tools.agents;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PeerMessenger;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.github.lscoughlin.jeorje.tools.registry.Required;

/** The {@code ask_peer} tool. It asks an agent on a peer of the swarm a question and waits for the answer. */
public final class AskPeerTool extends RecordTool<AskPeerTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "ask_peer";

    private static final long WAIT_LIMIT_SECONDS = 300;

    /** The input of {@code ask_peer}. */
    public record Input(
            @Required @Describe("The id or the name of a peer of the swarm.") String peer,
            @Describe("The agent on the peer. Empty means the main agent.") String agent,
            @Required @Describe("The question.") String question) {}

    private final PeerMessenger messenger;

    public AskPeerTool(PeerMessenger messenger) {
        super(NAME, "Ask an agent on another Jeorje instance of the swarm a question and wait for the answer. "
                + "The call fails with a reason when no answer can come. The question cannot approve a tool run on "
                + "the other instance.", Input.class, false);
        this.messenger = messenger;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) {
        var problem = check(NAME, messenger, input.peer(), input.question(), "question");
        if (problem != null) {
            return problem;
        }
        var future = messenger.ask(input.peer().strip(), SendMessageTool.blankToNull(input.agent()), input.question());
        try {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_LIMIT_SECONDS);
            while (true) {
                if (context.cancel().isCancelled()) {
                    future.cancel(true);
                    return ToolResultData.error(NAME + " failed: the call was cancelled.");
                }
                try {
                    return ToolResultData.ok(future.get(200, TimeUnit.MILLISECONDS));
                } catch (TimeoutException e) {
                    if (System.nanoTime() > deadline) {
                        future.cancel(true);
                        return ToolResultData.error(NAME + " failed: no answer came in time. Continue without it.");
                    }
                }
            }
        } catch (ExecutionException e) {
            return ToolResultData.error(NAME + " failed: " + reason(e.getCause() == null ? e : e.getCause())
                    + knownPeers(messenger));
        } catch (CancellationException e) {
            return ToolResultData.error(NAME + " failed: the call was cancelled.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResultData.error(NAME + " failed: the call was interrupted.");
        }
    }

    /** Checks the common fields. Returns an error result, or null when the fields are fine. */
    static ToolResultData check(String tool, PeerMessenger messenger, String peer, String body, String bodyName) {
        if (peer == null || peer.isBlank()) {
            return ToolResultData.error(tool + " failed: the field peer is missing." + knownPeers(messenger));
        }
        if (body == null || body.isBlank()) {
            return ToolResultData.error(tool + " failed: the field " + bodyName + " is missing.");
        }
        return null;
    }

    /** The sentence that lists the known peer ids. */
    static String knownPeers(PeerMessenger messenger) {
        var ids = messenger.peerIds();
        return ids.isEmpty() ? " No peer is known now." : " Known peers: " + String.join(", ", ids) + ".";
    }

    static String reason(Throwable error) {
        var message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
