package org.github.lscoughlin.jeorje.tools.memory;

import java.util.List;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.github.lscoughlin.jeorje.tools.registry.Required;

/** The {@code ask_user} tool. It sends a question event to the user and waits for the answer. */
public final class AskUserTool extends RecordTool<AskUserTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "ask_user";

    /** The input of {@code ask_user}. */
    public record Input(
            @Required @Describe("The question for the user.") String question,
            @Describe("Choices for the user. Leave empty for a free answer.") List<String> choices) {}

    private final UserQuestionBroker broker;

    public AskUserTool(UserQuestionBroker broker) {
        super(NAME, "Ask the user a question and wait for the answer. You can give choices.",
                Input.class, false);
        this.broker = broker;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) throws Exception {
        if (input.question().isBlank()) return ToolResultData.error("The question is empty.");
        var choices = input.choices() == null ? List.<String>of() : input.choices();
        var answer = broker.ask(context.agent(), input.question(), choices, context.cancel());
        return ToolResultData.ok("User answer: " + answer);
    }
}
