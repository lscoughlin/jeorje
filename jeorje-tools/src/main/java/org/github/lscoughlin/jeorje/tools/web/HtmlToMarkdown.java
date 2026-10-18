package org.github.lscoughlin.jeorje.tools.web;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A small HTML to markdown converter. It handles headings, links, lists, code blocks, and
 * tables. It does not need a parser library.
 */
public final class HtmlToMarkdown {
    private static final Set<String> SKIP = Set.of("script", "style", "head", "noscript", "svg", "template");
    private static final Set<String> BLOCK = Set.of("p", "div", "section", "article", "header", "footer",
            "main", "nav", "aside", "blockquote", "form", "figure", "figcaption", "ul", "ol", "table", "hr");
    private static final Set<String> VOID = Set.of("br", "hr", "img", "meta", "link", "input");
    private static final Pattern ATTR = Pattern.compile(
            "([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+)))?");
    private static final Map<String, String> ENTITIES = Map.of(
            "amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " ",
            "hellip", "...", "mdash", "--", "ndash", "-", "copy", "(c)");

    private HtmlToMarkdown() {}

    /** Converts an HTML text to markdown text. */
    public static String convert(String html) {
        return new Run(html).run();
    }

    private static final class Run {
        final String html;
        final StringBuilder out = new StringBuilder();
        final ArrayDeque<String> lists = new ArrayDeque<>(); // "ul" or "ol"
        final ArrayDeque<Integer> counters = new ArrayDeque<>();
        final ArrayDeque<String> hrefs = new ArrayDeque<>();
        final ArrayDeque<Integer> linkStarts = new ArrayDeque<>();
        String skipUntil;
        boolean pre;
        List<String> row;
        StringBuilder cell;
        boolean rowHasHeader;
        boolean headerEmitted;
        int tableRows;
        int skipDepth;

        Run(String html) {
            this.html = html;
        }

        String run() {
            var i = 0;
            var n = html.length();
            while (i < n) {
                var lt = html.indexOf('<', i);
                if (lt < 0) {
                    text(html.substring(i));
                    break;
                }
                if (lt > i) text(html.substring(i, lt));
                if (html.startsWith("<!--", lt)) {
                    var end = html.indexOf("-->", lt + 4);
                    i = end < 0 ? n : end + 3;
                    continue;
                }
                var gt = findTagEnd(lt);
                if (gt < 0) {
                    text(html.substring(lt));
                    break;
                }
                tag(html.substring(lt + 1, gt));
                i = gt + 1;
            }
            return tidy(out.toString());
        }

        int findTagEnd(int lt) {
            char quote = 0;
            for (var j = lt + 1; j < html.length(); j++) {
                var c = html.charAt(j);
                if (quote != 0) {
                    if (c == quote) quote = 0;
                } else if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '>') {
                    return j;
                }
            }
            return -1;
        }

        void tag(String raw) {
            if (raw.isEmpty() || raw.charAt(0) == '!' || raw.charAt(0) == '?') return;
            var closing = raw.charAt(0) == '/';
            if (closing) raw = raw.substring(1);
            var k = 0;
            while (k < raw.length() && !Character.isWhitespace(raw.charAt(k)) && raw.charAt(k) != '/') k++;
            var name = raw.substring(0, k).toLowerCase(Locale.ROOT);
            var attrs = raw.substring(k);
            if (skipUntil != null) {
                if (name.equals(skipUntil)) {
                    if (closing) {
                        if (--skipDepth == 0) skipUntil = null;
                    } else if (!attrs.endsWith("/")) {
                        skipDepth++;
                    }
                }
                return;
            }
            if (SKIP.contains(name) && !closing) {
                if (!attrs.endsWith("/")) {
                    skipUntil = name;
                    skipDepth = 1;
                }
                return;
            }
            if (closing) close(name); else open(name, attrs);
        }

        void open(String name, String attrs) {
            switch (name) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    blank();
                    out.append("#".repeat(name.charAt(1) - '0')).append(' ');
                }
                case "br" -> {
                    if (cell != null) cell.append(' '); else out.append('\n');
                }
                case "hr" -> {
                    blank();
                    out.append("---");
                    blank();
                }
                case "ul", "ol" -> {
                    if (lists.isEmpty()) blank(); else lineStart();
                    lists.push(name);
                    counters.push(0);
                }
                case "li" -> {
                    lineStart();
                    out.append("  ".repeat(Math.max(0, lists.size() - 1)));
                    if (!lists.isEmpty() && lists.peek().equals("ol")) {
                        var c = counters.pop() + 1;
                        counters.push(c);
                        out.append(c).append(". ");
                    } else {
                        out.append("- ");
                    }
                }
                case "pre" -> {
                    blank();
                    out.append("```\n");
                    pre = true;
                }
                case "code" -> {
                    if (!pre) out.append('`');
                }
                case "strong", "b" -> out.append("**");
                case "em", "i" -> out.append('*');
                case "a" -> {
                    hrefs.push(attr(attrs, "href"));
                    linkStarts.push(out.length());
                    out.append('[');
                }
                case "img" -> {
                    var alt = attr(attrs, "alt");
                    if (!alt.isEmpty()) out.append("![").append(alt).append("](").append(attr(attrs, "src")).append(')');
                }
                case "table" -> {
                    blank();
                    tableRows = 0;
                }
                case "tr" -> {
                    row = new ArrayList<>();
                    rowHasHeader = false;
                }
                case "td", "th" -> {
                    cell = new StringBuilder();
                    if (name.equals("th")) rowHasHeader = true;
                }
                default -> {
                    if (BLOCK.contains(name)) blank();
                }
            }
        }

        void close(String name) {
            switch (name) {
                case "h1", "h2", "h3", "h4", "h5", "h6", "p", "div", "section", "article", "header", "footer",
                        "main", "nav", "aside", "blockquote", "form", "figure", "figcaption" -> blank();
                case "ul", "ol" -> {
                    if (!lists.isEmpty()) {
                        lists.pop();
                        counters.pop();
                    }
                    if (lists.isEmpty()) blank(); else lineStart();
                }
                case "li" -> lineStart();
                case "pre" -> {
                    lineStart();
                    out.append("```");
                    pre = false;
                    blank();
                }
                case "code" -> {
                    if (!pre) out.append('`');
                }
                case "strong", "b" -> out.append("**");
                case "em", "i" -> out.append('*');
                case "a" -> {
                    if (!hrefs.isEmpty()) {
                        var href = hrefs.pop();
                        var start = linkStarts.pop();
                        var label = out.substring(start + 1).strip();
                        if (href.isEmpty() || href.startsWith("#") || label.isEmpty()) {
                            out.setLength(start);
                            out.append(label);
                        } else {
                            out.append("](").append(href).append(')');
                        }
                    }
                }
                case "td", "th" -> {
                    if (cell != null && row != null) row.add(cell.toString().strip().replace("|", "\\|"));
                    cell = null;
                }
                case "tr" -> {
                    if (row != null && !row.isEmpty()) {
                        lineStart();
                        out.append("| ").append(String.join(" | ", row)).append(" |\n");
                        if (tableRows == 0) {
                            out.append("|").append(" --- |".repeat(row.size())).append('\n');
                        }
                        tableRows++;
                    }
                    row = null;
                }
                case "table" -> blank();
                default -> {
                }
            }
        }

        void text(String raw) {
            if (skipUntil != null) return;
            var decoded = decode(raw);
            if (pre) {
                out.append(decoded);
                return;
            }
            var collapsed = decoded.replaceAll("\\s+", " ");
            if (cell != null) {
                cell.append(collapsed);
                return;
            }
            if (collapsed.isBlank()) {
                if (!collapsed.isEmpty() && out.length() > 0 && !endsWithWhitespace()) out.append(' ');
                return;
            }
            if (endsWithWhitespace() || atLineStart()) collapsed = collapsed.stripLeading();
            out.append(collapsed);
        }

        boolean endsWithWhitespace() {
            return out.length() > 0 && Character.isWhitespace(out.charAt(out.length() - 1));
        }

        boolean atLineStart() {
            return out.length() == 0 || out.charAt(out.length() - 1) == '\n';
        }

        void lineStart() {
            if (cell != null) return;
            stripTrailingSpaces();
            if (!atLineStart()) out.append('\n');
        }

        void blank() {
            if (cell != null) return;
            stripTrailingSpaces();
            if (out.length() == 0) return;
            if (!atLineStart()) out.append('\n');
            if (out.length() < 2 || out.charAt(out.length() - 2) != '\n') out.append('\n');
        }

        void stripTrailingSpaces() {
            while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') out.setLength(out.length() - 1);
        }

        static String attr(String attrs, String wanted) {
            var m = ATTR.matcher(attrs);
            while (m.find()) {
                if (m.group(1).equalsIgnoreCase(wanted)) {
                    for (var g = 2; g <= 4; g++) if (m.group(g) != null) return decode(m.group(g));
                    return "";
                }
            }
            return "";
        }

        static String tidy(String s) {
            var lines = s.split("\n", -1);
            var sb = new StringBuilder();
            var inFence = false;
            var blanks = 0;
            for (var line : lines) {
                if (line.startsWith("```")) inFence = !inFence;
                var l = inFence ? line : line.stripTrailing();
                if (l.isEmpty() && !inFence) {
                    if (++blanks > 1) continue;
                } else {
                    blanks = 0;
                }
                sb.append(l).append('\n');
            }
            return sb.toString().strip();
        }
    }

    static String decode(String s) {
        if (s.indexOf('&') < 0) return s;
        var sb = new StringBuilder();
        var i = 0;
        while (i < s.length()) {
            var c = s.charAt(i);
            if (c == '&') {
                var semi = s.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    var ent = s.substring(i + 1, semi);
                    String rep = null;
                    if (ent.startsWith("#")) {
                        try {
                            var cp = ent.startsWith("#x") || ent.startsWith("#X")
                                    ? Integer.parseInt(ent.substring(2), 16) : Integer.parseInt(ent.substring(1));
                            rep = new String(Character.toChars(cp));
                        } catch (IllegalArgumentException e) {
                            rep = null;
                        }
                    } else {
                        rep = ENTITIES.get(ent);
                    }
                    if (rep != null) {
                        sb.append(rep);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
