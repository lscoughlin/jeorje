package org.github.lscoughlin.jeorje.ui.commands;

import java.util.Optional;
import java.util.regex.Pattern;

/** A parsed slash command line: the name without the slash, and the rest of the line. */
public record ParsedCommand(String name, String arguments) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_:.-]*");

    /** Parses a line. Empty if the line is not a slash command, for example a path like {@code /usr/bin}. */
    public static Optional<ParsedCommand> parse(String line) {
        if (line == null) {
            return Optional.empty();
        }
        var text = line.strip();
        if (text.length() < 2 || text.charAt(0) != '/') {
            return Optional.empty();
        }
        var end = 1;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        var name = text.substring(1, end);
        if (!NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        return Optional.of(new ParsedCommand(name, text.substring(end).strip()));
    }
}
