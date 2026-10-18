package org.github.lscoughlin.jeorje.core.instructions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A custom slash command. */
public record Command(String name, String description, String argumentHint, String model, String body,
                      Path file, Scopes.Scope scope) {

    /** Expands $ARGUMENTS and $1, $2, ... in the body. Arguments split like a shell line. */
    public String render(String arguments) {
        var args = arguments == null ? "" : arguments.strip();
        var parts = split(args);
        var out = body.replace("$ARGUMENTS", args);
        // Replace higher numbers first so that $10 is not read as $1 followed by 0.
        for (var i = parts.size(); i >= 1; i--) {
            out = out.replace("$" + i, parts.get(i - 1));
        }
        return out.replaceAll("\\$\\d+", "");
    }

    static List<String> split(String line) {
        var out = new ArrayList<String>();
        var sb = new StringBuilder();
        var quote = 0;
        var inToken = false;
        for (var i = 0; i < line.length(); i++) {
            var c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < line.length()) {
                    sb.append(line.charAt(++i));
                } else {
                    sb.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (c == '\\' && i + 1 < line.length()) {
                sb.append(line.charAt(++i));
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(sb.toString());
                    sb.setLength(0);
                    inToken = false;
                }
            } else {
                sb.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            out.add(sb.toString());
        }
        return out;
    }
}
