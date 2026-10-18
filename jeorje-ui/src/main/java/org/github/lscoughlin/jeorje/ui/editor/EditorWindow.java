package org.github.lscoughlin.jeorje.ui.editor;

import java.util.function.Supplier;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;
import org.github.lscoughlin.jeorje.ui.mouse.ScrollBar;
import org.github.lscoughlin.jeorje.ui.mouse.ScrollModel;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * One text window: a view of a document with a cursor, a selection, and a scroll position. A window has a frame,
 * a scroll bar on the right, and an optional scroll bar at the bottom.
 */
final class EditorWindow implements EditorDocument.Listener {
    EditorDocument doc;
    int cursor;
    /** The other end of the selection, or -1 when nothing is selected. */
    int anchor = -1;
    /** The display column that vertical moves try to keep, or -1. */
    int desiredColumn = -1;
    /** True when the next draw must scroll the cursor into view. */
    boolean reveal = true;
    final ScrollModel scroll;
    /** The frame of the window, including the border. */
    Rect frame = new Rect(0, 0, 0, 0);
    ScrollBar verticalBar;
    private final Supplier<EditorOptions> options;

    // Cache for wrapped rows.
    private int[] firstRow = new int[0];
    private int rowsVersion = -1;
    private int rowsWidth = -1;
    private int rowsTab = -1;
    private int maxWidthVersion = -1;
    private int maxWidth;
    private int maxWidthTab = -1;

    EditorWindow(EditorDocument doc, int wheelLines, Supplier<EditorOptions> options) {
        this.doc = doc;
        this.options = options;
        this.scroll = new ScrollModel(wheelLines);
        doc.addListener(this);
    }

    void setDocument(EditorDocument next) {
        doc.removeListener(this);
        doc = next;
        doc.addListener(this);
        cursor = 0;
        anchor = -1;
        desiredColumn = -1;
        reveal = true;
        scroll.scrollTo(0);
        scroll.scrollSideways(-scroll.hOffset());
    }

    void dispose() {
        doc.removeListener(this);
    }

    @Override
    public void textChanged(int pos, int removedLength, int insertedLength) {
        cursor = adjust(cursor, pos, removedLength, insertedLength);
        if (anchor >= 0) {
            anchor = adjust(anchor, pos, removedLength, insertedLength);
        }
        cursor = Math.min(cursor, doc.buffer.length());
        if (anchor > doc.buffer.length()) {
            anchor = doc.buffer.length();
        }
    }

    private static int adjust(int offset, int pos, int removed, int inserted) {
        if (offset <= pos) {
            return offset;
        }
        if (offset >= pos + removed) {
            return offset - removed + inserted;
        }
        return pos;
    }

    EditorOptions opts() {
        return options.get();
    }

    /** Stops the merge of the next typed char with the last change. */
    void undoBreak() {
        doc.undo.breakMerge();
    }

    boolean hasSelection() {
        return anchor >= 0 && anchor != cursor;
    }

    int selectionStart() {
        return hasSelection() ? Math.min(anchor, cursor) : cursor;
    }

    int selectionEnd() {
        return hasSelection() ? Math.max(anchor, cursor) : cursor;
    }

    // ---- geometry ----

    int gutter() {
        if (!opts().lineNumbers()) {
            return 0;
        }
        var digits = Integer.toString(Math.max(99, doc.buffer.lineCount())).length();
        return digits + 1;
    }

    int textWidth() {
        return Math.max(1, frame.width() - 2 - gutter());
    }

    int textHeight() {
        return Math.max(1, frame.height() - 2);
    }

    /** The area of the text and the line numbers, in screen cells. */
    Rect textArea() {
        return new Rect(frame.x() + 1, frame.y() + 1, Math.max(1, frame.width() - 2), textHeight());
    }

    // ---- display columns ----

    int displayCol(String line, int chars) {
        var tab = opts().tabWidth();
        var col = 0;
        for (var i = 0; i < Math.min(chars, line.length()); i++) {
            col += line.charAt(i) == '\t' ? tab - col % tab : 1;
        }
        return col;
    }

    int displayWidth(String line) {
        return displayCol(line, line.length());
    }

    /** The index of the char that covers a display column. A column after the end gives the line length. */
    int charAtCol(String line, int column) {
        var tab = opts().tabWidth();
        var col = 0;
        for (var i = 0; i < line.length(); i++) {
            var w = line.charAt(i) == '\t' ? tab - col % tab : 1;
            if (column < col + w) {
                // Round to the nearer edge for wide tabs.
                return line.charAt(i) == '\t' && column - col >= w / 2 + 1 ? i + 1 : i;
            }
            col += w;
        }
        return line.length();
    }

    // ---- rows ----

    private void buildRows() {
        var o = opts();
        var width = textWidth();
        var v = doc.buffer.version();
        if (rowsVersion == v && rowsWidth == width && rowsTab == o.tabWidth()) {
            return;
        }
        var count = doc.buffer.lineCount();
        firstRow = new int[count + 1];
        var row = 0;
        for (var i = 0; i < count; i++) {
            firstRow[i] = row;
            row += displayWidth(doc.buffer.line(i)) / width + 1;
        }
        firstRow[count] = row;
        rowsVersion = v;
        rowsWidth = width;
        rowsTab = o.tabWidth();
    }

    int rowCount() {
        if (!opts().wrap()) {
            return doc.buffer.lineCount();
        }
        buildRows();
        return firstRow[doc.buffer.lineCount()];
    }

    int rowLine(int row) {
        if (!opts().wrap()) {
            return Math.clamp(row, 0, doc.buffer.lineCount() - 1);
        }
        buildRows();
        var lo = 0;
        var hi = doc.buffer.lineCount() - 1;
        while (lo < hi) {
            var mid = (lo + hi + 1) >>> 1;
            if (firstRow[mid] <= row) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    int rowStartColumn(int row) {
        if (!opts().wrap()) {
            return 0;
        }
        return (row - firstRow[rowLine(row)]) * textWidth();
    }

    int firstRowOfLine(int line) {
        if (!opts().wrap()) {
            return line;
        }
        buildRows();
        return firstRow[line];
    }

    /** The row that holds the cursor. */
    int cursorRow() {
        var line = doc.buffer.lineOf(cursor);
        var col = displayCol(doc.buffer.line(line), cursor - doc.buffer.lineStart(line));
        return opts().wrap() ? firstRowOfLine(line) + col / textWidth() : line;
    }

    int cursorColumn() {
        var line = doc.buffer.lineOf(cursor);
        return displayCol(doc.buffer.line(line), cursor - doc.buffer.lineStart(line));
    }

    /** The offset at a cell of the text area. The row and the column count from the top left cell. */
    int offsetAt(int row, int col) {
        var rows = rowCount();
        var r = Math.clamp(scroll.offset() + row, 0, rows - 1);
        var line = rowLine(r);
        var text = doc.buffer.line(line);
        var base = opts().wrap() ? rowStartColumn(r) : scroll.hOffset();
        var displayColumn = base + Math.max(0, col - gutter());
        return doc.buffer.lineStart(line) + charAtCol(text, displayColumn);
    }

    /** Updates the size of the content in the scroll model. Call it before draw and before scroll changes. */
    void sync() {
        var o = opts();
        scroll.setContent(rowCount(), textHeight());
        if (o.wrap()) {
            scroll.setHorizontal(0, textWidth());
        } else {
            scroll.setHorizontal(maxLineWidth() + 1, textWidth());
        }
        verticalBar = new ScrollBar(new Rect(frame.x() + frame.width() - 1, frame.y() + 1, 1, textHeight()), scroll);
    }

    boolean hasHorizontalBar() {
        return !opts().wrap() && maxLineWidth() + 1 > textWidth();
    }

    int maxLineWidth() {
        var o = opts();
        var v = doc.buffer.version();
        if (maxWidthVersion != v || maxWidthTab != o.tabWidth()) {
            var max = 0;
            for (var i = 0; i < doc.buffer.lineCount(); i++) {
                max = Math.max(max, displayWidth(doc.buffer.line(i)));
            }
            maxWidth = max;
            maxWidthVersion = v;
            maxWidthTab = o.tabWidth();
        }
        return maxWidth;
    }

    /** Scrolls so that the cursor is in view. */
    void revealCursor() {
        sync();
        var row = cursorRow();
        var height = textHeight();
        if (row < scroll.offset()) {
            scroll.shift(row - scroll.offset());
        } else if (row >= scroll.offset() + height) {
            scroll.shift(row - height + 1 - scroll.offset());
        }
        if (!opts().wrap()) {
            var col = cursorColumn();
            var width = textWidth();
            if (col < scroll.hOffset()) {
                scroll.scrollSideways(col - scroll.hOffset());
            } else if (col >= scroll.hOffset() + width) {
                scroll.scrollSideways(col - width + 1 - scroll.hOffset());
            }
        }
        reveal = false;
    }

    /** The screen cell of the cursor, or null when the cursor is out of view. */
    int[] cursorCell() {
        var row = cursorRow() - scroll.offset();
        if (row < 0 || row >= textHeight()) {
            return null;
        }
        var base = opts().wrap() ? rowStartColumn(cursorRow()) : scroll.hOffset();
        var col = cursorColumn() - base;
        if (col < 0 || col >= textWidth()) {
            return null;
        }
        return new int[] {frame.x() + 1 + gutter() + col, frame.y() + 1 + row};
    }

    // ---- drawing ----

    void draw(Canvas canvas, Palette palette, boolean active, String title) {
        sync();
        var x = frame.x();
        var y = frame.y();
        canvas.box(x, y, frame.width(), frame.height(), palette.frame());
        var shown = " " + title + " ";
        if (shown.length() > frame.width() - 4) {
            shown = " …" + shown.substring(Math.max(0, shown.length() - (frame.width() - 6))) ;
        }
        canvas.put(x + Math.max(1, (frame.width() - shown.length()) / 2), y, shown,
                active ? palette.title() : palette.frame());
        var rows = verticalBar.render();
        for (var i = 0; i < rows.size(); i++) {
            canvas.put(x + frame.width() - 1, y + 1 + i, rows.get(i), palette.scrollBar());
        }
        if (hasHorizontalBar()) {
            drawHorizontalBar(canvas, palette);
        }
        var o = opts();
        var area = textArea();
        canvas.fill(area.x(), area.y(), frame.width() - 2, area.height(), ' ', palette.text());
        var width = textWidth();
        var first = scroll.offset();
        var last = Math.min(rowCount() - 1, first + textHeight() - 1);
        if (last < first) {
            return;
        }
        var firstLine = rowLine(first);
        var lastLine = rowLine(last);
        var styled = doc.styledLines(firstLine, lastLine);
        var gutterWidth = gutter();
        for (var r = first; r <= last; r++) {
            var line = rowLine(r);
            var text = doc.buffer.line(line);
            var screenY = area.y() + (r - first);
            var rowStart = rowStartColumn(r);
            if (gutterWidth > 0 && (!o.wrap() || r == firstRowOfLine(line))) {
                var number = String.format("%" + (gutterWidth - 1) + "d ", line + 1);
                canvas.put(area.x(), screenY, number, palette.lineNumber());
            }
            var base = styled.get(line - firstLine);
            var display = displayLine(line, text, base, palette);
            var from = o.wrap() ? rowStart : scroll.hOffset();
            if (from < display.length()) {
                var to = Math.min(display.length(), from + width);
                canvas.put(area.x() + gutterWidth, screenY, display.substring(from, to));
            }
        }
    }

    private void drawHorizontalBar(Canvas canvas, Palette palette) {
        var y = frame.y() + frame.height() - 1;
        var x0 = frame.x() + 1;
        var w = frame.width() - 2;
        if (w < 3) {
            return;
        }
        canvas.put(x0, y, "◄", palette.scrollBar());
        canvas.put(x0 + w - 1, y, "►", palette.scrollBar());
        var track = w - 2;
        var total = scroll.hOffset();
        var content = maxLineWidth() + 1;
        var thumb = Math.clamp((long) track * textWidth() / content, 1, track);
        var maxOffset = Math.max(1, content - textWidth());
        var pos = (int) Math.round((double) (track - thumb) * total / maxOffset);
        for (var i = 0; i < track; i++) {
            canvas.put(x0 + 1 + i, y, i >= pos && i < pos + thumb ? ScrollBar.THUMB : ScrollBar.TRACK,
                    palette.scrollBar());
        }
    }

    /** Handles a click on the bottom scroll bar. {@code x} is the column inside the bar. */
    void clickHorizontalBar(int x) {
        var w = frame.width() - 2;
        if (x <= 0) {
            scroll.scrollSideways(-1);
        } else if (x >= w - 1) {
            scroll.scrollSideways(1);
        } else {
            var track = w - 2;
            var content = maxLineWidth() + 1;
            var thumb = Math.clamp((long) track * textWidth() / content, 1, track);
            var maxOffset = Math.max(1, content - textWidth());
            var pos = (int) Math.round((double) (track - thumb) * scroll.hOffset() / maxOffset);
            var t = x - 1;
            if (t < pos) {
                scroll.scrollSideways(-Math.max(1, textWidth() - 1));
            } else if (t >= pos + thumb) {
                scroll.scrollSideways(Math.max(1, textWidth() - 1));
            }
        }
    }

    /** The whole line as it appears on the screen: tabs expanded and the selection and colors applied. */
    private AttributedString displayLine(int line, String text, AttributedString styled, Palette palette) {
        var o = opts();
        var lineStart = doc.buffer.lineStart(line);
        var selStart = hasSelection() ? selectionStart() : Integer.MAX_VALUE;
        var selEnd = hasSelection() ? selectionEnd() : Integer.MIN_VALUE;
        var sb = new AttributedStringBuilder();
        var colored = styled.length() == text.length() && o.syntaxColors() && palette.colors();
        var col = 0;
        var tab = o.tabWidth();
        for (var i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            var selected = lineStart + i >= selStart && lineStart + i < selEnd;
            AttributedStyle style;
            if (selected) {
                style = palette.selection();
            } else if (colored) {
                style = palette.onText(styled.styleAt(i));
            } else {
                style = palette.text();
            }
            if (c == '\t') {
                var n = tab - col % tab;
                for (var k = 0; k < n; k++) {
                    var shown = ' ';
                    var cellStyle = style;
                    if (o.showWhitespace() && k == 0) {
                        shown = '»';
                        cellStyle = selected ? style : palette.whitespace();
                    }
                    sb.style(cellStyle).append(shown);
                }
                col += n;
            } else if (c == ' ' && o.showWhitespace()) {
                sb.style(selected ? style : palette.whitespace()).append('·');
                col++;
            } else {
                sb.style(style).append(c < 32 ? '?' : c);
                col++;
            }
        }
        var end = lineStart + text.length();
        if (selStart <= end && selEnd > end && line < doc.buffer.lineCount() - 1) {
            sb.style(palette.selection()).append(' ');
        }
        return sb.toAttributedString();
    }
}
