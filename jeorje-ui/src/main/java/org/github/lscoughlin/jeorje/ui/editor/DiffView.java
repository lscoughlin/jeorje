package org.github.lscoughlin.jeorje.ui.editor;

import com.github.difflib.text.DiffRow;
import com.github.difflib.text.DiffRowGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.github.lscoughlin.jeorje.core.diff.SplitText;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;
import org.github.lscoughlin.jeorje.ui.mouse.ScrollBar;
import org.github.lscoughlin.jeorje.ui.mouse.ScrollModel;
import org.jline.utils.AttributedStringBuilder;

/**
 * The view of {@code View > Show Changes}. It has two columns: the file on disk on the left and the text of the
 * editor on the right. Changed lines have a tint. The changed words in a line have a mark.
 */
final class DiffView {
    private static final char OPEN = '\u0001';
    private static final char CLOSE = '\u0002';

    /** One row of the view. */
    record Row(DiffRow.Tag tag, int oldNumber, int newNumber, String oldText, String newText) { }

    private final List<Row> rows;
    final ScrollModel scroll;
    ScrollBar bar;
    Rect area = new Rect(0, 0, 0, 0);
    private final String leftTitle;
    private final String rightTitle;

    DiffView(String disk, String editor, String leftTitle, String rightTitle, int wheelLines) {
        this.leftTitle = leftTitle;
        this.rightTitle = rightTitle;
        this.scroll = new ScrollModel(wheelLines);
        this.rows = compute(disk, editor);
    }

    private static List<Row> compute(String disk, String editor) {
        var a = SplitText.split(disk).lines();
        var b = SplitText.split(editor).lines();
        var generator = DiffRowGenerator.create()
                .showInlineDiffs(true)
                .inlineDiffByWord(true)
                .lineNormalizer(Function.identity())
                .oldTag(start -> start ? String.valueOf(OPEN) : String.valueOf(CLOSE))
                .newTag(start -> start ? String.valueOf(OPEN) : String.valueOf(CLOSE))
                .build();
        var out = new ArrayList<Row>();
        var oldNo = 1;
        var newNo = 1;
        for (var row : generator.generateDiffRows(a, b)) {
            switch (row.getTag()) {
                case EQUAL -> out.add(new Row(row.getTag(), oldNo++, newNo++, row.getOldLine(), row.getNewLine()));
                case DELETE -> out.add(new Row(row.getTag(), oldNo++, 0, row.getOldLine(), ""));
                case INSERT -> out.add(new Row(row.getTag(), 0, newNo++, "", row.getNewLine()));
                case CHANGE -> out.add(new Row(row.getTag(), oldNo++, newNo++, row.getOldLine(), row.getNewLine()));
            }
        }
        return out;
    }

    List<Row> rows() {
        return rows;
    }

    /** True when the two texts are equal. */
    boolean identical() {
        return rows.stream().allMatch(r -> r.tag() == DiffRow.Tag.EQUAL);
    }

    int changedRows() {
        return (int) rows.stream().filter(r -> r.tag() != DiffRow.Tag.EQUAL).count();
    }

    void layout(Rect where) {
        this.area = where;
        scroll.setContent(rows.size(), Math.max(1, where.height() - 2));
        bar = new ScrollBar(new Rect(where.x() + where.width() - 1, where.y() + 1, 1, Math.max(1, where.height() - 2)),
                scroll);
    }

    void draw(Canvas canvas, Palette palette) {
        var x = area.x();
        var y = area.y();
        canvas.fill(x, y, area.width(), area.height(), ' ', palette.text());
        canvas.box(x, y, area.width(), area.height(), palette.frame());
        var inner = area.width() - 2;
        var half = (inner - 1) / 2;
        var title = " Show Changes: " + leftTitle + " | " + rightTitle + " (Esc returns) ";
        canvas.put(x + Math.max(1, (area.width() - title.length()) / 2), y, title, palette.title());
        var height = area.height() - 2;
        for (var i = 0; i < height; i++) {
            canvas.set(x + 1 + half, y + 1 + i, '│', palette.frame());
        }
        if (identical()) {
            canvas.put(x + 2, y + 1, "No differences.", palette.text());
        }
        for (var i = 0; i < height; i++) {
            var index = scroll.offset() + i;
            if (index >= rows.size() || identical()) {
                continue;
            }
            var row = rows.get(index);
            drawSide(canvas, palette, x + 1, y + 1 + i, half, row.oldNumber(), row.oldText(), row.tag(), true);
            drawSide(canvas, palette, x + 2 + half, y + 1 + i, inner - half - 1, row.newNumber(), row.newText(),
                    row.tag(), false);
        }
        var barRows = bar.render();
        for (var i = 0; i < barRows.size(); i++) {
            canvas.put(x + area.width() - 1, y + 1 + i, barRows.get(i), palette.scrollBar());
        }
    }

    private void drawSide(Canvas canvas, Palette palette, int x, int y, int width, int number, String text,
                          DiffRow.Tag tag, boolean left) {
        var base = switch (tag) {
            case EQUAL -> palette.text();
            case CHANGE -> palette.diffChange();
            case INSERT -> left ? palette.text() : palette.diffAdd();
            case DELETE -> left ? palette.diffRemove() : palette.text();
        };
        canvas.fill(x, y, width, 1, ' ', base);
        if (number == 0) {
            return;
        }
        var prefix = String.format("%4d ", number);
        var sb = new AttributedStringBuilder();
        sb.style(base).append(prefix);
        var marked = false;
        for (var c : text.replace("\t", "    ").toCharArray()) {
            if (c == OPEN) {
                marked = true;
            } else if (c == CLOSE) {
                marked = false;
            } else {
                sb.style(marked ? palette.diffWord() : base).append(c);
            }
        }
        var line = sb.toAttributedString();
        canvas.put(x, y, line.length() > width ? line.substring(0, width) : line);
    }

    /** The text of the rows in the window, for tests and for the plain view. */
    List<String> plain(int width, int height) {
        var out = new ArrayList<String>();
        for (var i = 0; i < height && scroll.offset() + i < rows.size(); i++) {
            var row = rows.get(scroll.offset() + i);
            out.add(strip(row.oldText()) + " | " + strip(row.newText()));
        }
        return out;
    }

    static String strip(String text) {
        return text.replace(String.valueOf(OPEN), "").replace(String.valueOf(CLOSE), "");
    }

    /** The marked words of a row, joined with a space. */
    static List<String> marked(String text) {
        var out = new ArrayList<String>();
        var current = new StringBuilder();
        var inside = false;
        for (var c : text.toCharArray()) {
            if (c == OPEN) {
                inside = true;
            } else if (c == CLOSE) {
                inside = false;
                out.add(current.toString());
                current.setLength(0);
            } else if (inside) {
                current.append(c);
            }
        }
        return out;
    }
}
