package org.github.lscoughlin.jeorje.core.model;

import java.util.List;
import java.util.Objects;

/** One message of the conversation. */
public record Message(Role role, List<Content> content) {
    public Message {
        Objects.requireNonNull(role, "role");
        content = List.copyOf(content);
    }

    public static Message user(String text) { return new Message(Role.USER, List.of(new Text(text))); }

    public static Message assistant(String text) { return new Message(Role.ASSISTANT, List.of(new Text(text))); }
}
