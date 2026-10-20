package org.github.lscoughlin.jeorje.tools.memory;

import java.util.List;
import org.github.lscoughlin.jeorje.core.form.Form;
import org.github.lscoughlin.jeorje.core.form.FormOption;
import org.github.lscoughlin.jeorje.core.model.AgentId;

/**
 * A question event from an agent to the user. It holds a form.
 *
 * @param id the id that the answer must use
 */
public record UserQuestion(String id, AgentId agent, Form form) {
    /** A question with one prompt and choices, or an empty list for a free answer. */
    public UserQuestion(String id, AgentId agent, String question, List<String> choices) {
        this(id, agent, Form.single(question, choices));
    }

    /** The prompt of the first question. */
    public String question() { return form.firstPrompt(); }

    /** The labels of the options of the first question. */
    public List<String> choices() {
        return form.questions().isEmpty() ? List.of()
            : form.questions().getFirst().options().stream().map(FormOption::label).toList();
    }
}
