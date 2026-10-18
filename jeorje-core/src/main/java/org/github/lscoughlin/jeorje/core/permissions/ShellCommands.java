package org.github.lscoughlin.jeorje.core.permissions;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Splits shell commands into parts and checks parts against the read-only allow list. */
public final class ShellCommands {

    /** Text that replaces a command substitution in the outer part. */
    public static final String SUBSTITUTION = "SUBST";

    private static final Set<String> READ_ONLY = Set.of(
            "ls", "cat", "head", "tail", "pwd", "echo", "wc", "grep", "rg", "find", "sort", "uniq",
            "diff", "stat", "file", "which", "whoami", "date", "tree", "du", "df", "basename",
            "dirname", "realpath", "true", "false", "cut", "id", "uname", "printf");

    private static final Set<String> GIT_READ_ONLY = Set.of(
            "status", "log", "diff", "show", "rev-parse", "ls-files", "blame", "describe");

    private static final Set<String> FIND_WRITE_FLAGS = Set.of(
            "-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprint0", "-fprintf", "-fls");

    private ShellCommands() {
    }

    /**
     * Splits a command at {@code &&}, {@code ||}, {@code ;}, {@code |}, {@code &}, new lines, and
     * parentheses. The content of {@code $( )}, backticks, and {@code <( )} becomes its own parts.
     */
    public static List<String> split(String command) {
        var out = new ArrayList<String>();
        splitInto(command, out);
        return out;
    }

    private static void splitInto(String s, List<String> out) {
        var cur = new StringBuilder();
        var quote = (char) 0;
        var i = 0;
        while (i < s.length()) {
            var c = s.charAt(i);
            var next = i + 1 < s.length() ? s.charAt(i + 1) : (char) 0;
            if (quote == '\'') {
                cur.append(c);
                if (c == '\'') quote = 0;
                i++;
            } else if (c == '\\' && next != 0) {
                cur.append(c).append(next);
                i += 2;
            } else if (c == '$' && next == '(') {
                i = substitution(s, i + 2, out, cur);
            } else if ((c == '<' || c == '>') && next == '(' && quote == 0) {
                i = substitution(s, i + 2, out, cur);
            } else if (c == '`') {
                i = backtick(s, i + 1, out, cur);
            } else if (quote == '"') {
                cur.append(c);
                if (c == '"') quote = 0;
                i++;
            } else if (c == '\'' || c == '"') {
                quote = c;
                cur.append(c);
                i++;
            } else if (c == ';' || c == '\n' || c == '|' || c == '(' || c == ')') {
                flush(cur, out);
                i++;
            } else if (c == '&') {
                var prev = i > 0 ? s.charAt(i - 1) : (char) 0;
                if (prev == '>' || prev == '<' || next == '>') {
                    cur.append(c);
                } else {
                    flush(cur, out);
                }
                i++;
            } else {
                cur.append(c);
                i++;
            }
        }
        flush(cur, out);
    }

    private static int substitution(String s, int start, List<String> out, StringBuilder cur) {
        var depth = 1;
        var i = start;
        while (i < s.length() && depth > 0) {
            var c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            i++;
        }
        var end = depth == 0 ? i - 1 : s.length();
        splitInto(s.substring(start, end), out);
        cur.append(SUBSTITUTION);
        return depth == 0 ? i : s.length();
    }

    private static int backtick(String s, int start, List<String> out, StringBuilder cur) {
        var end = s.indexOf('`', start);
        var inner = end < 0 ? s.substring(start) : s.substring(start, end);
        splitInto(inner, out);
        cur.append(SUBSTITUTION);
        return end < 0 ? s.length() : end + 1;
    }

    private static void flush(StringBuilder cur, List<String> out) {
        var part = cur.toString().trim();
        cur.setLength(0);
        if (!part.isEmpty()) out.add(part);
    }

    /** True if the part is a safe read-only command. */
    public static boolean isReadOnly(String part) {
        if (writesToFile(part)) return false;
        var tokens = part.trim().split("\\s+");
        if (tokens.length == 0 || tokens[0].isEmpty() || tokens[0].contains("=")) return false;
        var word = tokens[0];
        if (word.equals("git")) {
            return tokens.length > 1 && GIT_READ_ONLY.contains(tokens[1]);
        }
        if (!READ_ONLY.contains(word)) return false;
        if (word.equals("find")) {
            for (var token : tokens) {
                if (FIND_WRITE_FLAGS.contains(token)) return false;
            }
        }
        return true;
    }

    /** True if the part has an unquoted output redirect, other than to /dev/null or a descriptor. */
    static boolean writesToFile(String part) {
        var quote = (char) 0;
        for (var i = 0; i < part.length(); i++) {
            var c = part.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '>') {
                var j = i;
                while (j < part.length() && part.charAt(j) == '>') j++;
                while (j < part.length() && part.charAt(j) == ' ') j++;
                var rest = part.substring(j);
                if (!rest.startsWith("&") && !rest.startsWith("/dev/null")) return true;
                i = j - 1;
            }
        }
        return false;
    }
}
