package org.github.lscoughlin.jeorje.ui.commands;

/**
 * The outcome of a line of input.
 *
 * @param handled true if the line was a slash command (known or unknown); false if it is a normal prompt
 * @param output  text to show in the chat; empty if there is none
 * @param error   true if the command failed
 */
public record CommandResult(boolean handled, String output, boolean error) {

    public static final CommandResult NOT_A_COMMAND = new CommandResult(false, "", false);

    public static CommandResult ok(String output) {
        return new CommandResult(true, output, false);
    }

    public static CommandResult fail(String output) {
        return new CommandResult(true, output, true);
    }
}
