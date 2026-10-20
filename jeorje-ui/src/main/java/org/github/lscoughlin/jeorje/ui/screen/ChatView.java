package org.github.lscoughlin.jeorje.ui.screen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.ui.chat.FenceHighlighter;
import org.github.lscoughlin.jeorje.ui.chat.HighlightContext;
import org.github.lscoughlin.jeorje.ui.chat.ResultRenderers;
import org.github.lscoughlin.jeorje.ui.chat.Verbosity;
import org.github.lscoughlin.jeorje.ui.mouse.OlderLines;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;
import org.github.lscoughlin.jeorje.ui.mouse.ScrollBar;
import org.github.lscoughlin.jeorje.ui.mouse.ScrollModel;
import org.github.lscoughlin.jeorje.ui.mouse.Selection;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * The scrolling conversation of one agent. It keeps at most {@link #MAX_LINES} logical lines.
 * All methods are thread safe.
 */
public final class ChatView {
    public static final int MAX_LINES = 10_000;

    private static final AttributedStyle USER = AttributedStyle.DEFAULT.bold().foreground(AttributedStyle.GREEN);
    private static final AttributedStyle TOOL = AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE);
    private static final AttributedStyle DIM = AttributedStyle.DEFAULT.faint();
    private static final AttributedStyle ERROR = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
    private static final AttributedStyle ADDED = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
    private static final AttributedStyle REMOVED = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
    private static final int COLLAPSED_PREVIEW = 60;

    /**
     * The columns that the scroll bar takes: the bar, and one empty column at the right edge. A line that fills the
     * last column puts the terminal in the state of a pending wrap, and the display then writes the next rows in the
     * wrong place.
     */
    public static final int BAR_COLUMNS = 2;

    private static final Pattern PATH_FIELD = Pattern.compile("\"(?:path|file_path|file)\"\\s*:\\s*\"([^\"]+)\"");
    private static final java.util.Set<String> RENDERED_TOOLS = java.util.Set.of("edit", "patch", "diff", "read",
            "grep", "bash", "webfetch");

    private abstract class Entry {
        private List<AttributedString> cache;

        final List<AttributedString> lines() {
            if (cache == null) {
                var built = build();
                cache = highlight != null && highlight.config().noColor() ? stripColors(built) : built;
            }
            return cache;
        }

        final void invalidate() { cache = null; }

        abstract List<AttributedString> build();
    }

    private final class TextEntry extends Entry {
        private final StringBuilder text = new StringBuilder();
        private final boolean markdown;
        private final AttributedStyle style;
        private final FenceHighlighter fence;

        TextEntry(String initial, boolean markdown, AttributedStyle style) {
            this.text.append(initial);
            this.markdown = markdown;
            this.style = style;
            this.fence = markdown && highlight != null ? new FenceHighlighter(highlight) : null;
            if (fence != null) fence.append(initial);
        }

        void append(String chunk) {
            text.append(chunk);
            if (fence != null) fence.append(chunk);
            invalidate();
        }

        @Override
        List<AttributedString> build() {
            if (fence != null) {
                var styled = fence.lines();
                var flags = fence.outsideFlags();
                var out = new ArrayList<AttributedString>(styled.size());
                for (var i = 0; i < styled.size(); i++) {
                    out.add(flags.get(i) ? Markdown.render(styled.get(i).toString()).get(0) : styled.get(i));
                }
                return out;
            }
            if (markdown) return Markdown.render(text.toString());
            var out = new ArrayList<AttributedString>();
            for (var l : text.toString().split("\n", -1)) out.add(new AttributedString(l, style));
            return out;
        }
    }

    private final class ToolEntry extends Entry {
        final String id;
        final String tool;
        final String summary;
        ToolResultData result;
        boolean expanded;

        ToolEntry(String id, String tool, String summary) {
            this.id = id;
            this.tool = tool;
            this.summary = summary;
        }

        @Override
        List<AttributedString> build() {
            var out = new ArrayList<AttributedString>();
            if (verbosity == Verbosity.QUIET) {
                // The user asked for no tool noise. Failures and approvals still show, because they need an answer.
                if (result == null || !result.error()) return out;
                var failed = result.output().split("\n", -1)[0];
                out.add(new AttributedString("! " + tool + " " + oneLine(failed), ERROR));
                return out;
            }
            var head = new AttributedStringBuilder();
            head.append("▶ ", TOOL).append(tool, TOOL.bold());
            if (summary != null && !summary.isBlank()) head.append(" ").append(oneLine(summary), DIM);
            out.add(head.toAttributedString());
            if (result == null) return out;
            var style = result.error() ? ERROR : DIM;
            var all = result.output().split("\n", -1);
            if (expanded || verbosity == Verbosity.VERBOSE) {
                if (renderers != null && !result.error() && RENDERED_TOOLS.contains(tool)) {
                    for (var l : renderers.render(id, tool, pathOf(summary), result.output())) {
                        out.add(indent(l));
                    }
                } else {
                    for (var l : all) out.add(styleResultLine(l, style));
                }
            } else {
                var first = oneLine(all[0]);
                if (first.length() > COLLAPSED_PREVIEW) first = first.substring(0, COLLAPSED_PREVIEW) + "…";
                var more = all.length > 1 ? " (" + all.length + " lines, Ctrl-X o to expand)" : "";
                out.add(new AttributedString("  ⎿ " + first + more, style));
            }
            return out;
        }

        private AttributedString indent(AttributedString line) {
            var sb = new AttributedStringBuilder();
            sb.append("  ");
            sb.append(line);
            return sb.toAttributedString();
        }

        private AttributedString styleResultLine(String line, AttributedStyle base) {
            if (!result.error() && "edit".equals(tool)) {
                if (line.startsWith("+") && !line.startsWith("+++")) return new AttributedString("  " + line, ADDED);
                if (line.startsWith("-") && !line.startsWith("---")) return new AttributedString("  " + line, REMOVED);
            }
            return new AttributedString("  " + line.replace("\t", "    "), base);
        }
    }

    /**
     * What a wrapped line shows, for a mouse click.
     *
     * @param toolUseId the tool call that owns the line, or null for other lines
     * @param togglesResult true when a click on the line (outside a path) expands or collapses the result
     * @param text the text of the whole logical line, with no colors
     * @param column the column of the click in the logical line
     */
    public record LineRef(String toolUseId, boolean togglesResult, String text, int column) {}

    private final HighlightContext highlight;
    private final ResultRenderers renderers;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final List<AttributedString> head = new ArrayList<>();
    private final Selection selection = new Selection();
    private volatile Verbosity verbosity = Verbosity.NORMAL;
    private TextEntry streaming;
    private ToolEntry lastTool;
    private int scrollBack;
    private int newLines;
    private boolean scrollBar;
    private ScrollModel barModel;
    private OlderLines older;
    private int olderIndex;
    private int lastWidth;
    private int lastHeight;
    private int lastStart;
    private int lastTotal;

    /** Creates a chat view with no highlighting. */
    public ChatView() {
        this(null);
    }

    /**
     * Creates a chat view with highlighting.
     *
     * @param highlight the shared highlighting state (settings from {@code ui.chat.highlight}), or null for none
     */
    public ChatView(HighlightContext highlight) {
        this.highlight = highlight;
        this.renderers = highlight == null ? null : new ResultRenderers(highlight);
    }

    /** Adds a message of the user. */
    public synchronized void appendUser(String text) {
        streaming = null;
        var before = measure();
        add(new TextEntry("> " + text, false, USER));
        grew(before);
    }

    /** Adds a plain notice line, for example a command output. */
    public synchronized void appendNotice(String text) {
        streaming = null;
        var before = measure();
        add(new TextEntry(text, false, DIM));
        grew(before);
    }

    /**
     * Sets how much of the work of the agent the view shows. A change redraws the tool calls that the view already
     * holds.
     *
     * @param level the new level, or null for {@link Verbosity#NORMAL}
     */
    public synchronized void verbosity(Verbosity level) {
        verbosity = level == null ? Verbosity.NORMAL : level;
        for (var entry : entries) entry.invalidate();
    }

    /** The level that the view shows. */
    public Verbosity verbosity() { return verbosity; }

    /**
     * Removes all lines, together with the selection, the scroll position, and any partly streamed text. The source
     * of older lines stays, because it holds the stored transcript and not the lines of the view.
     */
    public synchronized void clear() {
        entries.clear();
        head.clear();
        streaming = null;
        lastTool = null;
        scrollBack = 0;
        newLines = 0;
        selection.clear();
    }

    /** Applies one agent event. */
    public synchronized void apply(AgentEvent event) {
        var before = measure();
        applyEvent(event);
        grew(before);
    }

    private void applyEvent(AgentEvent event) {
        switch (event) {
            case AgentEvent.TokenDelta d -> {
                if (streaming == null) {
                    streaming = new TextEntry("", true, AttributedStyle.DEFAULT);
                    add(streaming);
                }
                streaming.append(d.text());
                trim();
            }
            case AgentEvent.ToolStarted s -> {
                streaming = null;
                lastTool = new ToolEntry(s.toolUseId(), s.tool(), s.summary());
                add(lastTool);
            }
            case AgentEvent.ToolFinished f -> {
                var entry = findTool(f.toolUseId());
                if (entry == null) {
                    entry = new ToolEntry(f.toolUseId(), "tool", "");
                    add(entry);
                }
                entry.result = f.result();
                entry.invalidate();
                lastTool = entry;
                trim();
            }
            case AgentEvent.ConfirmationRequested c -> {
                streaming = null;
                add(new TextEntry("? waiting for approval: " + c.toolUse().tool(), false, DIM));
            }
            case AgentEvent.TurnFinished t -> streaming = null;
            case AgentEvent.Compacted c -> {
                streaming = null;
                add(new TextEntry("[compacted: " + c.chunksPruned() + " chunks, " + c.tokensFreed()
                        + " tokens freed]", false, DIM));
            }
            case AgentEvent.ContextUsage u -> { }
            case AgentEvent.ContextTrimmed t -> {
                streaming = null;
                var text = new StringBuilder("[context: ");
                if (t.prunedChunks() > 0) text.append(t.prunedChunks()).append(" chunks pruned to fit the budget");
                if (!t.droppedTurns().isEmpty()) {
                    text.append(t.prunedChunks() > 0 ? ", " : "").append(t.droppedTurns().size() == 1 ? "turn " : "turns ")
                        .append(t.droppedTurns().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")))
                        .append(" left the request (use recall)");
                }
                if (t.overBudget()) text.append(t.prunedChunks() > 0 || !t.droppedTurns().isEmpty() ? "; " : "")
                    .append("the request is still over the budget");
                add(new TextEntry(text.append(']').toString(), false, ERROR));
            }
            case AgentEvent.Error e -> {
                streaming = null;
                add(new TextEntry("! " + e.message(), false, ERROR));
            }
            case AgentEvent.Steered s -> {
                // The input line of the interface already shows the text of the user. A second line would repeat it.
                streaming = null;
            }
            case AgentEvent.Cleared c -> {
                clear();
                var before = measure();
                add(new TextEntry("New conversation. " + c.turns() + " earlier turns left the context.", false, DIM));
                grew(before);
            }
        }
    }

    /** Expands or collapses the result of the last tool call. Returns false if there is none. */
    public synchronized boolean toggleLastResult() {
        if (lastTool == null || lastTool.result == null) return false;
        lastTool.expanded = !lastTool.expanded;
        lastTool.invalidate();
        trim();
        return true;
    }

    /** Expands or collapses the result of one tool call. Returns false if the call is unknown. */
    public synchronized boolean toggleResult(String toolUseId) {
        var entry = findTool(toolUseId);
        if (entry == null || entry.result == null) return false;
        entry.expanded = !entry.expanded;
        entry.invalidate();
        trim();
        return true;
    }

    /** True if the result of the tool call is expanded. */
    public synchronized boolean isExpanded(String toolUseId) {
        var entry = findTool(toolUseId);
        return entry != null && entry.expanded;
    }

    /** The logical lines (not wrapped), at most {@link #MAX_LINES}. */
    public synchronized List<AttributedString> logicalLines() {
        var out = new ArrayList<AttributedString>();
        for (var e : entries) out.addAll(e.lines());
        var capped = out.size() > MAX_LINES ? new ArrayList<>(out.subList(out.size() - MAX_LINES, out.size())) : out;
        if (head.isEmpty()) return capped;
        var all = new ArrayList<AttributedString>(head.size() + capped.size());
        all.addAll(head);
        all.addAll(capped);
        return all;
    }

    /** The text of the logical lines, joined with newlines. It has no escape codes. Use it for copy and history. */
    public synchronized String plainText() {
        var sb = new StringBuilder();
        for (var line : logicalLines()) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(line.toString());
        }
        return sb.toString();
    }

    /** The number of logical lines. */
    public synchronized int lineCount() {
        var n = 0;
        for (var e : entries) n += e.lines().size();
        return Math.min(n, MAX_LINES) + head.size();
    }

    /** The lines with soft wrap. Wrap keeps the styles. */
    public synchronized List<AttributedString> wrapped(int width) {
        var w = Math.max(1, width);
        var out = new ArrayList<AttributedString>();
        for (var line : logicalLines()) {
            if (line.length() == 0) out.add(AttributedString.EMPTY);
            else out.addAll(line.columnSplitLength(w));
        }
        return out;
    }

    /**
     * The lines that fit in the window, counted from the bottom and moved up by the scroll offset. With a scroll
     * bar, the text uses {@code width - 2} columns, then comes the bar, then one empty column. The result then has
     * exactly {@code height} rows.
     */
    public synchronized List<AttributedString> visible(int width, int height) {
        var contentWidth = scrollBar ? Math.max(1, width - BAR_COLUMNS) : width;
        if (contentWidth != lastWidth && selection.isActive()) selection.clear();
        var all = wrapped(contentWidth);
        var max = Math.max(0, all.size() - height);
        scrollBack = Math.min(scrollBack, max);
        if (scrollBack == 0) newLines = 0;
        var end = all.size() - scrollBack;
        var start = Math.max(0, end - height);
        lastWidth = Math.max(1, contentWidth);
        lastHeight = height;
        lastStart = start;
        lastTotal = all.size();
        var out = new ArrayList<AttributedString>(height);
        for (var i = start; i < end; i++) out.add(withSelection(all.get(i), i));
        if (!scrollBar) return out;
        barModel.setContent(all.size(), height);
        barModel.shift(start - barModel.offset());
        var glyphs = new ScrollBar(new Rect(0, 0, 1, height), barModel).render();
        while (out.size() < height) out.add(AttributedString.EMPTY);
        var framed = new ArrayList<AttributedString>(height);
        for (var row = 0; row < height; row++) {
            var sb = new AttributedStringBuilder();
            var line = out.get(row);
            sb.append(line);
            sb.append(" ".repeat(Math.max(0, contentWidth - line.columnLength())));
            sb.append(glyphs.get(row), DIM);
            sb.append(" ".repeat(BAR_COLUMNS - 1));
            framed.add(sb.toAttributedString());
        }
        return framed;
    }

    private AttributedString withSelection(AttributedString line, int index) {
        var cols = selection.columnsOf(index, line.length());
        if (cols == null) return line;
        var sb = new AttributedStringBuilder();
        for (var i = 0; i < line.length(); i++) {
            var style = line.styleAt(i);
            sb.style(i >= cols[0] && i < cols[1] ? style.inverse() : style);
            sb.append(line.charAt(i));
        }
        return sb.toAttributedString();
    }

    public synchronized void scrollUp(int lines) {
        scrollBack = Math.max(0, scrollBack + lines);
        if (lastHeight > 0 && scrollBack > Math.max(0, lastTotal - lastHeight)) loadOlder();
    }

    public synchronized void scrollDown(int lines) { scrollBack = Math.max(0, scrollBack - lines); }

    /** Jumps to the bottom, follows new lines again, and clears the count of new lines. */
    public synchronized void scrollToEnd() {
        scrollBack = 0;
        newLines = 0;
    }

    /** True when the view follows the newest line. */
    public synchronized boolean following() { return scrollBack == 0; }

    /** The number of lines that arrived while the view was scrolled up. */
    public synchronized int newLines() { return scrollBack == 0 ? 0 : newLines; }

    /** The notice for new lines, or null when the view follows. {@code key} is the key of the follow action. */
    public synchronized String followNotice(String key) {
        if (scrollBack == 0 || newLines == 0) return null;
        return newLines + " new lines - " + key + " or click here to follow";
    }

    // ---- mouse support ----

    /**
     * Turns the scroll bar on or off. The bar takes the last column of the chat area.
     *
     * @param wheelLines the lines for a wheel step over the bar
     */
    public synchronized void scrollBar(boolean on, int wheelLines) {
        scrollBar = on;
        barModel = on ? new ScrollModel(Math.max(1, wheelLines)) : null;
    }

    public synchronized boolean hasScrollBar() { return scrollBar; }

    /** The scroll model of the bar, or null when the bar is off. The bar region moves it. */
    public synchronized ScrollModel barModel() { return barModel; }

    /** Takes the position that the bar region set. Call it after each event of the bar. */
    public synchronized void syncFromBar() {
        if (barModel == null) return;
        scrollBack = Math.max(0, barModel.maxOffset() - barModel.offset());
        if (scrollBack == 0) newLines = 0;
    }

    /**
     * Sets the source of older lines (the transcript). The view loads a block of lines from it when the user
     * scrolls above the first line.
     *
     * @param firstIndex the number of lines in the source that come before the first line of the view
     */
    public synchronized void olderLines(OlderLines source, int firstIndex) {
        this.older = source;
        this.olderIndex = Math.max(0, firstIndex);
    }

    /** The index in the source of the first loaded older line. */
    public synchronized int olderIndex() { return olderIndex; }

    /** Loads one block of older lines above the first line. Returns the number of lines that it loaded. */
    public synchronized int loadOlder() {
        if (older == null || olderIndex <= 0) return 0;
        List<String> block;
        try {
            block = older.before(olderIndex, org.github.lscoughlin.jeorje.ui.mouse.ChatScroll.BLOCK);
        } catch (RuntimeException e) {
            return 0;
        }
        if (block.isEmpty()) {
            olderIndex = 0;
            return 0;
        }
        var lines = new ArrayList<AttributedString>(block.size());
        var rows = 0;
        for (var text : block) {
            var line = new AttributedString(text.replace("\t", "    "), DIM);
            lines.add(line);
            rows += lastWidth > 0 ? Math.max(1, (line.length() + lastWidth - 1) / lastWidth) : 1;
        }
        head.addAll(0, lines);
        olderIndex = Math.max(0, olderIndex - block.size());
        lastTotal += rows;
        selection.clear();
        return block.size();
    }

    /** The selection, in the numbers of the wrapped lines. */
    public Selection selection() { return selection; }

    /** The geometry of the last draw: the first wrapped line, the rows, and the text columns. */
    public synchronized int[] geometry() { return new int[] {lastStart, lastHeight, lastWidth, lastTotal}; }

    /** The wrapped line number of a row of the last draw, limited to the existing lines. */
    public synchronized int lineAtRow(int row) {
        return Math.clamp((long) lastStart + Math.clamp(row, 0, Math.max(0, lastHeight - 1)), 0,
                Math.max(0, lastTotal - 1));
    }

    /** The text of one wrapped line, with no colors. */
    public synchronized String wrappedText(int index) {
        var all = wrapped(Math.max(1, lastWidth == 0 ? 80 : lastWidth));
        return index >= 0 && index < all.size() ? all.get(index).toString() : "";
    }

    /** The selected text. It has no escape codes. */
    public synchronized String selectedText() {
        if (!selection.isActive()) return "";
        var plain = new ArrayList<String>();
        for (var line : wrapped(Math.max(1, lastWidth == 0 ? 80 : lastWidth))) plain.add(line.toString());
        return selection.text(plain);
    }

    /** Describes the wrapped line at a row of the last draw, for a click. Returns null for an empty view. */
    public synchronized LineRef refAtRow(int row, int column) {
        var target = lineAtRow(row);
        var width = Math.max(1, lastWidth == 0 ? 80 : lastWidth);
        var seen = 0;
        var logicalLines = new ArrayList<Object[]>();
        for (var l : head) logicalLines.add(new Object[] {null, Boolean.FALSE, l});
        var all = new ArrayList<Entry>(entries);
        var capped = 0;
        for (var e : all) capped += e.lines().size();
        var skip = Math.max(0, capped - MAX_LINES);
        for (var e : all) {
            var lines = e.lines();
            for (var i = 0; i < lines.size(); i++) {
                if (skip > 0) {
                    skip--;
                    continue;
                }
                String id = null;
                var toggles = false;
                if (e instanceof ToolEntry t && t.result != null) {
                    id = t.id;
                    toggles = i == 0 || !t.expanded;
                }
                logicalLines.add(new Object[] {id, toggles, lines.get(i)});
            }
        }
        for (var entry : logicalLines) {
            var line = (AttributedString) entry[2];
            var rows = line.length() == 0 ? 1 : line.columnSplitLength(width).size();
            if (target < seen + rows) {
                return new LineRef((String) entry[0], (Boolean) entry[1], line.toString(),
                        (target - seen) * width + column);
            }
            seen += rows;
        }
        return null;
    }

    private int measure() {
        return scrollBack > 0 && lastWidth > 0 ? wrapped(lastWidth).size() : -1;
    }

    /** Keeps the view still when lines arrive below it, and counts them for the notice. */
    private void grew(int before) {
        if (before < 0) return;
        var delta = wrapped(lastWidth).size() - before;
        if (delta > 0) {
            scrollBack += delta;
            newLines += delta;
        }
    }

    private void add(Entry e) {
        entries.addLast(e);
        trim();
    }

    private void trim() {
        var total = 0;
        for (var e : entries) total += e.lines().size();
        while (total > MAX_LINES && entries.size() > 1) {
            var removed = entries.removeFirst();
            total -= removed.lines().size();
        }
    }

    private ToolEntry findTool(String id) {
        for (var it = entries.descendingIterator(); it.hasNext(); ) {
            if (it.next() instanceof ToolEntry t && t.id.equals(id)) return t;
        }
        return null;
    }

    private static List<AttributedString> stripColors(List<AttributedString> lines) {
        var out = new ArrayList<AttributedString>(lines.size());
        for (var line : lines) {
            var sb = new AttributedStringBuilder();
            for (var i = 0; i < line.length(); i++) {
                var style = line.styleAt(i);
                sb.style(style.foregroundOff().backgroundOff());
                sb.append(line.charAt(i));
            }
            out.add(sb.toAttributedString());
        }
        return out;
    }

    private static String pathOf(String summary) {
        if (summary == null) return null;
        var m = PATH_FIELD.matcher(summary);
        if (m.find()) return m.group(1);
        var text = summary.strip();
        return text.isEmpty() || text.matches(".*[\\s{}\"].*") ? null : text;
    }

    private static String oneLine(String s) { return s.replace('\n', ' ').replace("\t", " ").strip(); }
}
