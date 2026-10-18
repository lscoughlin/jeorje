package org.github.lscoughlin.jeorje.ui.commands;

/** A completion for the input line. {@code text} is the full replacement for the line. */
public record Candidate(String text, String description) {
}
