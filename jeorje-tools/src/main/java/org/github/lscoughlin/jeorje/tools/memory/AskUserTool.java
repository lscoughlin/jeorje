package org.github.lscoughlin.jeorje.tools.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.github.lscoughlin.jeorje.core.form.Form;
import org.github.lscoughlin.jeorje.core.form.FormAnswer;
import org.github.lscoughlin.jeorje.core.form.FormOption;
import org.github.lscoughlin.jeorje.core.form.FormQuestion;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Allowed;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import tools.jackson.databind.JsonNode;

/**
 * The {@code ask_user} tool. It shows a form to the user and waits for the answer. A call with {@code question}
 * and {@code choices} is a form with one question.
 */
public final class AskUserTool extends RecordTool<AskUserTool.Input> {
    /** The name of the tool. */
    public static final String NAME = "ask_user";

    /** One question of a form. */
    public record QuestionInput(
            @Describe("Id of the question. The answer uses it. Default q1, q2, and so on.") String id,
            @Describe("The question for the user.") String prompt,
            @Allowed({"single", "multi", "text"})
            @Describe("single: one option. multi: several options. text: a free answer. "
                    + "Default single when options exist, else text.") String kind,
            @Describe("Options. Each is a text label or an object with label and description.") List<JsonNode> options,
            @Describe("Let the user type an answer that is not an option. Default false.") Boolean allowOther,
            @Describe("The user must answer. Default true.") Boolean required,
            @Describe("multi: the least number of options. Default 0.") Integer minChoices,
            @Describe("multi: the most options. Default no limit.") Integer maxChoices,
            @Describe("single: the label of the option that starts chosen. text: the start text.") String defaultValue) {}

    /** The input of {@code ask_user}. */
    public record Input(
            @Describe("Short title of the form.") String title,
            @Describe("The questions of the form, at most 8. Use this or question.") List<QuestionInput> questions,
            @Describe("One question for the user. Use this or questions.") String question,
            @Describe("Choices for the one question. Leave empty for a free answer.") List<String> choices) {}

    private final UserQuestionBroker broker;

    public AskUserTool(UserQuestionBroker broker) {
        super(NAME, "Ask the user one or more questions in a form and wait for the answers. A question can be "
                + "single choice, multiple choice, or free text. The user can also choose to chat about the "
                + "question first. Then the result says so, and you answer in chat and ask again if you need to.",
                Input.class, false);
        this.broker = broker;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) throws Exception {
        var legacy = input.question() != null;
        Form form;
        if (legacy) {
            if (input.questions() != null && !input.questions().isEmpty()) {
                return ToolResultData.error("Use question or questions, not both.");
            }
            if (input.question().isBlank()) return ToolResultData.error("The question is empty.");
            form = Form.single(input.question(), input.choices() == null ? List.of() : input.choices());
        } else {
            if (input.questions() == null || input.questions().isEmpty()) {
                return ToolResultData.error("Give question or questions.");
            }
            var built = new ArrayList<FormQuestion>();
            for (var i = 0; i < input.questions().size(); i++) {
                var q = input.questions().get(i);
                var result = build(q, i + 1);
                if (result instanceof String error) return ToolResultData.error(error);
                built.add((FormQuestion) result);
            }
            form = new Form(input.title(), built);
        }
        var problem = form.validate();
        if (problem.isPresent()) return ToolResultData.error(problem.get());
        var answer = broker.ask(context.agent(), form, context.cancel());
        if (legacy && answer.status() == FormAnswer.Status.ANSWERED) {
            return ToolResultData.ok("User answer: " + answer.firstValue());
        }
        return ToolResultData.ok(answer.render(form));
    }

    /** Returns a {@link FormQuestion} or an error text. */
    private static Object build(QuestionInput q, int number) {
        var options = new ArrayList<FormOption>();
        for (var node : q.options() == null ? List.<JsonNode>of() : q.options()) {
            if (node.isString()) {
                options.add(FormOption.of(node.asString()));
            } else if (node.isObject() && node.path("label").isString()) {
                var d = node.path("description");
                options.add(new FormOption(node.path("label").asString(), d.isString() ? d.asString() : null));
            } else {
                return "An option must be a text or an object with a label.";
            }
        }
        FormQuestion.Kind kind;
        if (q.kind() == null) {
            kind = options.isEmpty() ? FormQuestion.Kind.TEXT : FormQuestion.Kind.SINGLE;
        } else {
            try {
                kind = FormQuestion.Kind.valueOf(q.kind().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return "Unknown kind: " + q.kind() + ". Use single, multi, or text.";
            }
        }
        var id = q.id() == null || q.id().isBlank() ? "q" + number : q.id();
        return new FormQuestion(id, q.prompt() == null ? "" : q.prompt(), kind, options,
            Boolean.TRUE.equals(q.allowOther()), q.required() == null || q.required(),
            q.minChoices() == null ? 0 : q.minChoices(), q.maxChoices() == null ? 0 : q.maxChoices(),
            q.defaultValue());
    }
}
