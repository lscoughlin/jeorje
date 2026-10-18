package org.github.lscoughlin.jeorje.ui.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;
import org.github.lscoughlin.jeorje.ui.mouse.Region;
import org.github.lscoughlin.jeorje.ui.mouse.RegionEvent;
import org.github.lscoughlin.jeorje.ui.mouse.ScrollModel;
import org.jline.terminal.MouseEvent;

/**
 * A small tree browser at the working directory. It hides {@code .git/} and the paths of {@code .gitignore}
 * unless the user presses {@code .}. It is a display filter only. It is not a sandbox.
 */
public final class FileBrowser implements Region {
    /** One visible row. */
    public record Row(Path path, int depth, boolean directory, boolean expanded) {}

    private final Path root;
    private final IgnoreRules ignore;
    private final ScrollModel scroll;
    private final Set<Path> expanded = new HashSet<>();
    private Rect bounds;
    private boolean open;
    private boolean showIgnored;
    private boolean filtering;
    private String filter = "";
    private int selected;
    private Consumer<Path> opener = p -> { };
    private Consumer<Path> adder = p -> { };
    private Consumer<Path> picker;

    public FileBrowser(Path root, Rect bounds, int wheelLines) {
        this.root = root.toAbsolutePath().normalize();
        this.ignore = IgnoreRules.load(this.root);
        this.bounds = bounds;
        this.scroll = new ScrollModel(wheelLines);
    }

    /** Sets the action for Enter on a file (opens the editor). */
    public void onOpen(Consumer<Path> opener) { this.opener = opener; }

    /** Sets a picker. While it is set, Enter on a file calls it and does not call the opener. Null clears it. */
    public void onPick(Consumer<Path> picker) { this.picker = picker; }

    /** Sets the action for {@code a} (adds a path reference to the prompt). */
    public void onAdd(Consumer<Path> adder) { this.adder = adder; }

    public void setBounds(Rect bounds) { this.bounds = bounds; }

    @Override
    public Rect bounds() { return bounds; }

    public boolean isOpen() { return open; }

    public void open() { open = true; }

    public void close() { open = false; }

    public String filter() { return filter; }

    public boolean isFiltering() { return filtering; }

    public boolean showsIgnored() { return showIgnored; }

    public int selectedIndex() { return selected; }

    public int scrollOffset() { return scroll.offset(); }

    public ScrollModel scroll() { return scroll; }

    public Row selectedRow() {
        var rows = rows();
        return rows.isEmpty() ? null : rows.get(Math.clamp(selected, 0, rows.size() - 1));
    }

    // ---- tree ----

    /** The visible rows in tree order. */
    public List<Row> rows() {
        var out = new ArrayList<Row>();
        collect(root, 0, out);
        return out;
    }

    private void collect(Path dir, int depth, List<Row> out) {
        for (var child : children(dir)) {
            var isDir = Files.isDirectory(child);
            if (filter.isEmpty()) {
                var open = isDir && expanded.contains(child);
                out.add(new Row(child, depth, isDir, open));
                if (open) collect(child, depth + 1, out);
            } else if (isDir) {
                var sub = new ArrayList<Row>();
                collect(child, depth + 1, sub);
                if (matches(child) || !sub.isEmpty()) {
                    out.add(new Row(child, depth, true, true));
                    out.addAll(sub);
                }
            } else if (matches(child)) {
                out.add(new Row(child, depth, false, false));
            }
        }
    }

    private boolean matches(Path p) {
        return p.getFileName().toString().toLowerCase().contains(filter.toLowerCase());
    }

    private List<Path> children(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(this::visible)
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private boolean visible(Path p) {
        if (showIgnored) return true;
        if (p.getFileName().toString().equals(".git")) return false;
        return !ignore.ignored(root.relativize(p), Files.isDirectory(p));
    }

    /** The text rows for the visible part of the tree. */
    public List<String> render() {
        var rows = rows();
        var out = new ArrayList<String>();
        var from = scroll.offset();
        var to = Math.min(rows.size(), from + Math.max(1, bounds.height()));
        for (var i = from; i < to; i++) {
            var r = rows.get(i);
            var mark = r.directory() ? (r.expanded() ? "v " : "> ") : "  ";
            var name = r.path().getFileName() + (r.directory() ? "/" : "");
            out.add((i == selected ? "*" : " ") + "  ".repeat(r.depth()) + mark + name);
        }
        return out;
    }

    // ---- actions ----

    public void up() { select(selected - 1); }

    public void down() { select(selected + 1); }

    private void select(int index) {
        var rows = rows();
        if (rows.isEmpty()) return;
        selected = Math.clamp(index, 0, rows.size() - 1);
        sync(rows.size());
        if (selected < scroll.offset()) scroll.shift(selected - scroll.offset());
        else if (selected >= scroll.offset() + bounds.height()) {
            scroll.shift(selected - (scroll.offset() + bounds.height() - 1));
        }
    }

    private void sync(int count) { scroll.setContent(count, bounds.height()); }

    /** Opens the selected directory. */
    public void right() {
        var row = selectedRow();
        if (row != null && row.directory()) expanded.add(row.path());
    }

    /** Closes the selected directory. On a closed directory or a file, selects the parent directory. */
    public void left() {
        var row = selectedRow();
        if (row == null) return;
        if (row.directory() && expanded.remove(row.path())) return;
        var parent = row.path().getParent();
        var rows = rows();
        for (var i = 0; i < rows.size(); i++) {
            if (rows.get(i).path().equals(parent)) {
                select(i);
                return;
            }
        }
    }

    /** Enter: opens a file in the editor, or toggles a directory. */
    public void enter() {
        var row = selectedRow();
        if (row == null) return;
        if (row.directory()) {
            if (!expanded.remove(row.path())) expanded.add(row.path());
        } else if (picker != null) {
            picker.accept(row.path());
        } else {
            opener.accept(row.path());
        }
    }

    /** Adds the selected path to the prompt. */
    public void addSelected() {
        var row = selectedRow();
        if (row != null) adder.accept(row.path());
    }

    public void toggleIgnored() {
        showIgnored = !showIgnored;
        select(selected);
    }

    public void setFilter(String text) {
        filter = text;
        select(0);
    }

    /**
     * Handles a key. Key names are UP, DOWN, LEFT, RIGHT, ENTER, ESC, BACKSPACE, or a single character. In
     * filter mode, characters go into the filter. Returns true when the key was used.
     */
    public boolean handleKey(String key) {
        if (!open) return false;
        if (filtering) {
            switch (key) {
                case "ENTER" -> filtering = false;
                case "ESC" -> {
                    filtering = false;
                    setFilter("");
                }
                case "BACKSPACE" -> {
                    if (!filter.isEmpty()) setFilter(filter.substring(0, filter.length() - 1));
                }
                case "UP" -> up();
                case "DOWN" -> down();
                default -> {
                    if (key.length() == 1) setFilter(filter + key);
                    else return false;
                }
            }
            return true;
        }
        switch (key) {
            case "UP" -> up();
            case "DOWN" -> down();
            case "RIGHT" -> right();
            case "LEFT" -> left();
            case "ENTER" -> enter();
            case "a" -> addSelected();
            case "/" -> filtering = true;
            case "." -> toggleIgnored();
            case "ESC" -> close();
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---- mouse ----

    @Override
    public void onMouse(RegionEvent event) {
        var rows = rows();
        sync(rows.size());
        if (event.isWheel()) {
            scroll.wheel(event.button(), event.shift());
        } else if (event.type() == MouseEvent.Type.Pressed && event.button() == MouseEvent.Button.Button1) {
            var index = scroll.offset() + event.y();
            if (event.y() < 0 || index >= rows.size()) return;
            selected = index;
            if (event.clicks() >= 2) enter();
        }
    }
}
