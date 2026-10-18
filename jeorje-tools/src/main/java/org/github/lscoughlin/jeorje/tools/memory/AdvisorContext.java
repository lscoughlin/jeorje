package org.github.lscoughlin.jeorje.tools.memory;

import java.util.List;
import org.github.lscoughlin.jeorje.core.model.Message;

/**
 * The working context of an agent as the advisor sees it. The messages already hold the keyword
 * hints of the pruned chunks.
 */
public record AdvisorContext(String system, List<Message> messages) {
    public AdvisorContext { messages = List.copyOf(messages); }
}
