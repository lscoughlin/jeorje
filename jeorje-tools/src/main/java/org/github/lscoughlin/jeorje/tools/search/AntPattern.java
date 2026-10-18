package org.github.lscoughlin.jeorje.tools.search;

import java.util.regex.Pattern;

/**
 * An Ant-style path pattern. {@code *} matches any characters except {@code /}. {@code ?} matches
 * one such character. {@code **} as a whole segment matches zero or more directories. A pattern
 * that ends with {@code /} means {@code /**}. Paths use {@code /} as separator.
 */
final class AntPattern {
    private final Pattern regex;

    AntPattern(String pattern) {
        this.regex = Pattern.compile(translate(pattern));
    }

    boolean matches(String relativePath) {
        return regex.matcher(relativePath).matches();
    }

    static String translate(String pattern) {
        var p = pattern.replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        if (p.startsWith("/")) p = p.substring(1);
        if (p.endsWith("/")) p = p + "**";
        var out = new StringBuilder();
        var i = 0;
        while (i < p.length()) {
            var c = p.charAt(i);
            if (c == '*') {
                if (i + 1 < p.length() && p.charAt(i + 1) == '*') {
                    var atSegmentStart = i == 0 || p.charAt(i - 1) == '/';
                    var end = i + 2;
                    var atSegmentEnd = end == p.length() || p.charAt(end) == '/';
                    if (atSegmentStart && atSegmentEnd) {
                        if (end < p.length()) {
                            out.append("(?:.*/)?");
                            i = end + 1;
                        } else {
                            out.append(".*");
                            i = end;
                        }
                        continue;
                    }
                    out.append("[^/]*");
                    i = end;
                    continue;
                }
                out.append("[^/]*");
            } else if (c == '?') {
                out.append("[^/]");
            } else {
                out.append(Pattern.quote(String.valueOf(c)));
            }
            i++;
        }
        return out.toString();
    }
}
