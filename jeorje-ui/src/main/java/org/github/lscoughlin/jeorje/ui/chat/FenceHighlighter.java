package org.github.lscoughlin.jeorje.ui.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.github.lscoughlin.jeorje.ui.syntax.LineHighlighter;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxInfo;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

/**
 * Highlights the fenced code blocks of assistant text while the text streams.
 *
 * <p>Each complete line gets its colors once. The line in progress shows as plain text until its newline
 * arrives. Each block has one highlighter, so multi-line regions stay correct. A fence that has not closed
 * yet is highlighted as far as it has arrived. Lines outside the blocks are plain here. The caller styles
 * the Markdown text.
 */
public final class FenceHighlighter {

    private static final Pattern FENCE = Pattern.compile("^( {0,3})(`{3,}|~{3,})(.*)$");
    private static final AttributedStyle FENCE_STYLE = AttributedStyle.DEFAULT.faint();

    private enum Mode { TEXT, UNDECIDED, CODE, DIFF, PLAIN }

    private final HighlightContext context;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();
    private final List<AttributedString> done = new ArrayList<>();
    private final List<Boolean> outside = new ArrayList<>();

    private Mode mode = Mode.TEXT;
    private char fenceChar;
    private int fenceLength;
    private SyntaxInfo syntax;
    private LineHighlighter highlighter;
    private DiffRenderer diff;
    private int blockLines;
    private boolean limitNoted;

    public FenceHighlighter(HighlightContext context) {
        this.context = context;
    }

    /** Adds the next piece of text. */
    public synchronized void append(String chunk) {
        text.append(chunk);
        pending.append(chunk);
        int newline;
        while ((newline = pending.indexOf("\n")) >= 0) {
            var line = pending.substring(0, newline);
            pending.delete(0, newline + 1);
            var wasText = mode == Mode.TEXT;
            var styled = process(line);
            done.add(styled);
            outside.add(wasText && mode == Mode.TEXT);
        }
    }

    /**
     * For each line of {@link #lines()}: true when the line is Markdown text outside the fenced blocks. The
     * fence lines and the lines of the blocks give false. The caller styles the lines that give true.
     */
    public synchronized List<Boolean> outsideFlags() {
        var out = new ArrayList<>(outside);
        if (!pending.isEmpty() || done.isEmpty()) {
            out.add(mode == Mode.TEXT);
        }
        return out;
    }

    /** The original text, with no escape codes. */
    public synchronized String text() {
        return text.toString();
    }

    /** The styled lines: the complete lines and the plain line in progress. */
    public synchronized List<AttributedString> lines() {
        var out = new ArrayList<>(done);
        if (!pending.isEmpty() || done.isEmpty()) {
            out.add(new AttributedString(HighlightContext.display(pending.toString())));
        }
        return out;
    }

    private AttributedString process(String line) {
        var m = FENCE.matcher(line);
        var fenceLine = m.matches();
        if (mode == Mode.TEXT) {
            if (fenceLine && !(m.group(2).charAt(0) == '`' && m.group(3).indexOf('`') >= 0)) {
                openBlock(m.group(2), m.group(3));
                return new AttributedString(HighlightContext.display(line), FENCE_STYLE);
            }
            return new AttributedString(HighlightContext.display(line));
        }
        if (fenceLine && m.group(2).charAt(0) == fenceChar && m.group(2).length() >= fenceLength
                && m.group(3).isBlank()) {
            closeBlock();
            return new AttributedString(HighlightContext.display(line), FENCE_STYLE);
        }
        return blockLine(line);
    }

    private void openBlock(String marker, String info) {
        fenceChar = marker.charAt(0);
        fenceLength = marker.length();
        blockLines = 0;
        limitNoted = false;
        syntax = null;
        highlighter = null;
        diff = null;
        var config = context.config();
        var tag = LanguageResolver.tagOf(info);
        if (tag.isEmpty()) {
            mode = config.diffRendering() ? Mode.UNDECIDED : Mode.PLAIN;
            return;
        }
        if (!config.enabled() || !config.fences()) {
            mode = Mode.PLAIN;
            return;
        }
        if (tag.equals("diff") || tag.equals("patch")) {
            mode = config.diffRendering() ? Mode.DIFF : Mode.PLAIN;
            if (mode == Mode.DIFF) {
                diff = new DiffRenderer(context, null);
            }
            return;
        }
        var resolved = context.resolver().resolve(info);
        if (resolved.isEmpty()) {
            mode = Mode.PLAIN;
            return;
        }
        syntax = resolved.get();
        highlighter = context.open(syntax);
        mode = highlighter == null ? Mode.PLAIN : Mode.CODE;
    }

    private void closeBlock() {
        mode = Mode.TEXT;
        syntax = null;
        highlighter = null;
        diff = null;
    }

    private AttributedString blockLine(String line) {
        if (mode == Mode.UNDECIDED) {
            if (line.isBlank()) {
                return new AttributedString(HighlightContext.display(line));
            }
            if (DiffRenderer.looksLikeDiffStart(line)) {
                mode = Mode.DIFF;
                diff = new DiffRenderer(context, null);
            } else {
                mode = Mode.PLAIN;
            }
        }
        blockLines++;
        if (blockLines > context.config().maxLines() && mode != Mode.PLAIN) {
            if (!limitNoted) {
                limitNoted = true;
                done.add(new AttributedString("[Colors stop after " + context.config().maxLines() + " lines.]",
                        FENCE_STYLE));
                outside.add(false);
            }
            return new AttributedString(HighlightContext.display(line));
        }
        return switch (mode) {
            case CODE -> context.highlight(syntax, highlighter, line);
            case DIFF -> diff.line(line);
            default -> new AttributedString(HighlightContext.display(line));
        };
    }
}
