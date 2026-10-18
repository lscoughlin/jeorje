package org.github.lscoughlin.jeorje.ui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/** Basic markdown styling: headings, lists, code blocks, inline code, and bold text. */
final class Markdown {
    static final AttributedStyle HEADING = AttributedStyle.DEFAULT.bold().foreground(AttributedStyle.YELLOW);
    static final AttributedStyle CODE = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
    static final AttributedStyle FENCE = AttributedStyle.DEFAULT.faint();
    static final AttributedStyle BOLD = AttributedStyle.DEFAULT.bold();

    private static final Pattern HEADING_LINE = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern LIST_LINE = Pattern.compile("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$");

    private Markdown() {}

    /** Styles the text. The result has one string for each logical line. Code lines keep their whitespace. */
    static List<AttributedString> render(String text) {
        var out = new ArrayList<AttributedString>();
        var inFence = false;
        for (var raw : text.split("\n", -1)) {
            var line = raw.replace("\t", "    ");
            if (line.stripLeading().startsWith("```")) {
                inFence = !inFence;
                out.add(new AttributedString(line, FENCE));
                continue;
            }
            if (inFence) {
                out.add(new AttributedString(line, CODE));
                continue;
            }
            var heading = HEADING_LINE.matcher(line);
            if (heading.matches()) {
                out.add(new AttributedString(heading.group(1) + " " + heading.group(2), HEADING));
                continue;
            }
            var list = LIST_LINE.matcher(line);
            if (list.matches()) {
                var marker = Character.isDigit(list.group(2).charAt(0)) ? list.group(2) : "•";
                var sb = new AttributedStringBuilder();
                sb.append(list.group(1)).append(marker, AttributedStyle.DEFAULT.bold()).append(" ");
                inline(sb, list.group(3));
                out.add(sb.toAttributedString());
                continue;
            }
            var sb = new AttributedStringBuilder();
            inline(sb, line);
            out.add(sb.toAttributedString());
        }
        return out;
    }

    private static void inline(AttributedStringBuilder sb, String s) {
        var i = 0;
        var n = s.length();
        while (i < n) {
            var c = s.charAt(i);
            if (c == '`') {
                var end = s.indexOf('`', i + 1);
                if (end > i) {
                    sb.append(s.substring(i + 1, end), CODE);
                    i = end + 1;
                    continue;
                }
            } else if (c == '*' && s.startsWith("**", i)) {
                var end = s.indexOf("**", i + 2);
                if (end > i + 2) {
                    sb.append(s.substring(i + 2, end), BOLD);
                    i = end + 2;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
    }
}
