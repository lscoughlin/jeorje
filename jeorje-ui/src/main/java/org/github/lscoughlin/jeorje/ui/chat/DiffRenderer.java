package org.github.lscoughlin.jeorje.ui.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.github.lscoughlin.jeorje.ui.syntax.LineHighlighter;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxInfo;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * Renders a unified diff line by line.
 *
 * <p>Header lines are bold. Hunk lines are cyan. Added lines have a green tint and removed lines have a red
 * tint. The text after the marker has the syntax colors of the file. Removed lines and added lines use two
 * separate highlighters, so a comment that opens in a removed line does not color the added lines. With fewer
 * colors, or with {@code NO_COLOR}, the tint changes to a bold marker.
 */
public final class DiffRenderer {

    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
    private static final Pattern UNIFIED_START = Pattern.compile(
            "^(diff --git |--- a/|\\+\\+\\+ b/|@@ -\\d+(,\\d+)? \\+\\d+(,\\d+)? @@).*");

    static final AttributedStyle HEADER = AttributedStyle.DEFAULT.bold();
    static final AttributedStyle HUNK_STYLE = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
    private static final int[] ADD_TINT = {0, 48, 0};
    private static final int[] DEL_TINT = {64, 0, 0};

    private final HighlightContext context;
    private SyntaxInfo hint;
    private SyntaxInfo syntax;
    private LineHighlighter removed;
    private LineHighlighter added;
    private String oldPath;
    private boolean inHunk;
    private boolean unlimited;
    private int oldRemaining;
    private int newRemaining;

    /**
     * Creates a renderer.
     *
     * @param context  the shared state
     * @param filePath the file from the tool input, or null when the {@code +++} header gives it
     */
    public DiffRenderer(HighlightContext context, String filePath) {
        this.context = context;
        this.hint = context.resolver().forFile(filePath).orElse(null);
        open(hint);
    }

    /** True when the first lines of the text look like a unified diff. */
    public static boolean looksLikeDiff(String text) {
        var seen = 0;
        for (var line : text.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            if (looksLikeDiffStart(line)) {
                return true;
            }
            if (++seen >= 8) {
                return false;
            }
        }
        return false;
    }

    /** True when the line is one of the lines that open a unified diff. */
    public static boolean looksLikeDiffStart(String line) {
        return UNIFIED_START.matcher(line).matches();
    }

    /** Renders all lines. */
    public List<AttributedString> renderAll(List<String> lines) {
        var out = new ArrayList<AttributedString>(lines.size());
        for (var line : lines) {
            out.add(line(line));
        }
        return out;
    }

    /** Renders the next line of the diff. */
    public AttributedString line(String raw) {
        var line = HighlightContext.display(raw);
        if (!context.config().diffRendering()) {
            return new AttributedString(line);
        }
        if (line.startsWith("@@")) {
            startHunk(line);
            return new AttributedString(line, hunkStyle());
        }
        if (inHunk) {
            var styled = contentLine(line);
            if (styled != null) {
                if (!unlimited && oldRemaining <= 0 && newRemaining <= 0) {
                    inHunk = false;
                }
                return styled;
            }
            inHunk = false;
        }
        if (line.startsWith("diff ")) {
            newFile();
            var parts = line.split(" ");
            if (parts.length >= 4) {
                useFile(stripPrefix(parts[parts.length - 1]));
            }
            return new AttributedString(line, HEADER);
        }
        if (line.startsWith("--- ")) {
            oldPath = pathOf(line.substring(4));
            return new AttributedString(line, HEADER);
        }
        if (line.startsWith("+++ ")) {
            var path = pathOf(line.substring(4));
            useFile(path.equals("/dev/null") ? oldPath : path);
            return new AttributedString(line, HEADER);
        }
        if (line.startsWith("index ") || line.startsWith("new file") || line.startsWith("deleted file")
                || line.startsWith("similarity ") || line.startsWith("rename ")) {
            return new AttributedString(line, HEADER);
        }
        return new AttributedString(line);
    }

    private AttributedString contentLine(String line) {
        if (line.startsWith("\\")) {
            return new AttributedString(line, AttributedStyle.DEFAULT.faint());
        }
        if (line.isEmpty()) {
            countContext();
            return new AttributedString(line);
        }
        if (unlimited && (line.startsWith("--- a/") || line.startsWith("+++ b/") || line.startsWith("--- /dev/null")
                || line.startsWith("+++ /dev/null"))) {
            return null;
        }
        var marker = line.charAt(0);
        var text = line.substring(1);
        switch (marker) {
            case '+' -> {
                newRemaining--;
                return decorated('+', text, context.highlight(syntax, added, text), ADD_TINT, AttributedStyle.GREEN);
            }
            case '-' -> {
                oldRemaining--;
                return decorated('-', text, context.highlight(syntax, removed, text), DEL_TINT, AttributedStyle.RED);
            }
            case ' ' -> {
                countContext();
                // A context line is part of both streams. Both highlighters see it, one result is shown.
                context.highlight(syntax, removed, text);
                var styled = context.highlight(syntax, added, text);
                var sb = new AttributedStringBuilder();
                sb.append(' ').append(styled);
                return sb.toAttributedString();
            }
            default -> {
                return null;
            }
        }
    }

    private void countContext() {
        oldRemaining--;
        newRemaining--;
    }

    private AttributedString decorated(char marker, String text, AttributedString styled, int[] tint, int color) {
        var config = context.config();
        var sb = new AttributedStringBuilder();
        if (config.noColor()) {
            sb.append(String.valueOf(marker), AttributedStyle.DEFAULT.bold());
            sb.append(text);
        } else if (config.fewColors()) {
            sb.append(String.valueOf(marker), AttributedStyle.DEFAULT.bold().foreground(color));
            sb.append(styled);
        } else {
            var markerStyle = AttributedStyle.DEFAULT.bold().foreground(color).background(tint[0], tint[1], tint[2]);
            sb.append(String.valueOf(marker), markerStyle);
            for (var i = 0; i < styled.length(); i++) {
                sb.append(String.valueOf(styled.charAt(i)), styled.styleAt(i).background(tint[0], tint[1], tint[2]));
            }
        }
        return sb.toAttributedString();
    }

    private AttributedStyle hunkStyle() {
        return context.config().noColor() ? AttributedStyle.DEFAULT.bold() : HUNK_STYLE;
    }

    private void startHunk(String line) {
        var m = HUNK.matcher(line);
        inHunk = true;
        if (m.matches()) {
            unlimited = false;
            oldRemaining = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            newRemaining = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
            if (oldRemaining == 0 && newRemaining == 0) {
                inHunk = false;
            }
        } else {
            unlimited = true;
            oldRemaining = 0;
            newRemaining = 0;
        }
    }

    private void newFile() {
        inHunk = false;
        syntax = hint;
        oldPath = null;
        open(hint);
    }

    private void useFile(String path) {
        Optional<SyntaxInfo> found = path == null ? Optional.empty() : context.resolver().forFile(path);
        var next = found.orElse(hint);
        open(next);
    }

    private void open(SyntaxInfo next) {
        syntax = next;
        removed = context.open(next);
        added = context.open(next);
    }

    private static String pathOf(String header) {
        var text = header.strip();
        var tab = text.indexOf('\t');
        if (tab >= 0) {
            text = text.substring(0, tab);
        }
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            text = text.substring(1, text.length() - 1);
        }
        return stripPrefix(text);
    }

    private static String stripPrefix(String path) {
        return path.startsWith("a/") || path.startsWith("b/") ? path.substring(2) : path;
    }
}
