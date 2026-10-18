package org.github.lscoughlin.jeorje.core.instructions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses the {@code allowed-tools} field of a skill. */
public final class AllowedTools {

    /** The default Jeorje tool names. */
    public static final Set<String> DEFAULT_TOOLS = Set.of("read", "write", "edit", "bash", "grep", "glob", "ls",
            "webfetch", "websearch", "skill", "spawn_agent", "patch");

    /**
     * One allowed tool.
     *
     * @param tool        lower-case tool name
     * @param pattern     null for the whole tool; else a glob pattern (for bash with prefix=true: the command prefix)
     * @param bashPrefix  true when the pattern is a command prefix such as "git "
     */
    public record Grant(String tool, String pattern, boolean bashPrefix) {
    }

    /** Result of parsing. {@code ignored} has the tool names that are not Jeorje tools. */
    public record Parsed(List<Grant> grants, List<String> ignored) {
    }

    private AllowedTools() {
    }

    public static Parsed parse(String field) {
        return parse(field, DEFAULT_TOOLS);
    }

    public static Parsed parse(String field, Set<String> knownTools) {
        var grants = new ArrayList<Grant>();
        var ignored = new ArrayList<String>();
        if (field == null || field.isBlank()) {
            return new Parsed(grants, ignored);
        }
        for (var token : tokens(field)) {
            var open = token.indexOf('(');
            var rawName = open < 0 ? token : token.substring(0, open);
            var name = rawName.toLowerCase(Locale.ROOT);
            if (!knownTools.contains(name)) {
                ignored.add(rawName);
                continue;
            }
            if (open < 0) {
                grants.add(new Grant(name, null, false));
                continue;
            }
            var close = token.lastIndexOf(')');
            var pattern = token.substring(open + 1, close > open ? close : token.length()).strip();
            if (name.equals("bash") && pattern.endsWith(":*")) {
                grants.add(new Grant(name, pattern.substring(0, pattern.length() - 2) + " ", true));
            } else {
                grants.add(new Grant(name, pattern, false));
            }
        }
        return new Parsed(grants, ignored);
    }

    /** Splits on whitespace outside parentheses. */
    private static List<String> tokens(String field) {
        var out = new ArrayList<String>();
        var sb = new StringBuilder();
        var depth = 0;
        for (var c : field.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
            }
            if (Character.isWhitespace(c) && depth == 0) {
                if (!sb.isEmpty()) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
            } else {
                sb.append(c);
            }
        }
        if (!sb.isEmpty()) {
            out.add(sb.toString());
        }
        return out;
    }
}
