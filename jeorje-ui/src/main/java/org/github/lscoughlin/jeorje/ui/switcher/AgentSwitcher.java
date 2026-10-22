package org.github.lscoughlin.jeorje.ui.switcher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.core.directory.AgentDirectory;
import org.github.lscoughlin.jeorje.core.directory.AgentEntry;
import org.github.lscoughlin.jeorje.core.directory.RosterKind;
import org.github.lscoughlin.jeorje.core.directory.RosterNode;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;
import org.github.lscoughlin.jeorje.ui.mouse.Region;
import org.github.lscoughlin.jeorje.ui.mouse.RegionEvent;
import org.github.lscoughlin.jeorje.ui.mouse.ScrollModel;
import org.jline.terminal.MouseEvent;

/**
 * The tree of harnesses, agents, and subagents, and the foreground agent. A harness is one instance of Jeorje.
 * Each level of the tree has an indent of two spaces. A node with children shows {@code -} when it is open and
 * {@code +} when it is collapsed. Left collapses the selected node and Right expands it. The tree is a mouse
 * region. The wheel scrolls the tree and does not change the selected row. A click selects a row. Enter on an
 * agent or a subagent makes it the foreground agent. Enter on a harness does nothing. The foreground view shows
 * the full history of the agent and then new output.
 */
public final class AgentSwitcher implements Region {
    private final AgentDirectory directory;
    private final ScrollModel scroll;
    private final Deque<AgentId> trail = new ArrayDeque<>();
    private Rect bounds;
    private int selected;
    private String selectedId = "";
    private final java.util.Set<String> collapsed = new java.util.HashSet<>();
    private boolean open;
    private AgentId foreground;
    private final List<String> view = new ArrayList<>();
    private Consumer<AgentId> onForeground = id -> { };

    public AgentSwitcher(AgentDirectory directory, AgentId rootAgent, Rect bounds, int wheelLines) {
        this.directory = directory;
        this.bounds = bounds;
        this.scroll = new ScrollModel(wheelLines);
        this.foreground = rootAgent;
        reloadView();
    }

    /** Sets a listener that runs when the foreground agent changes. */
    public void onForeground(Consumer<AgentId> listener) { this.onForeground = listener; }

    public void setBounds(Rect bounds) {
        this.bounds = bounds;
        syncScroll();
    }

    @Override
    public Rect bounds() { return bounds; }

    public boolean isOpen() { return open; }

    /** One visible row of the tree. */
    private record Row(RosterNode node, int depth) {}

    /** The nodes of the directory. A directory without a tree gives a flat tree of its agents. */
    private List<RosterNode> nodes() {
        var tree = directory.tree();
        if (!tree.isEmpty()) return tree;
        var entries = directory.agents();
        var ids = new java.util.HashSet<AgentId>();
        entries.forEach(e -> ids.add(e.id()));
        var out = new ArrayList<RosterNode>();
        for (var entry : entries) {
            if (entry.parent() == null || !ids.contains(entry.parent())) {
                out.add(flat(RosterKind.AGENT, entry, entries, new java.util.HashSet<>()));
            }
        }
        return out;
    }

    private RosterNode flat(RosterKind kind, AgentEntry entry, List<AgentEntry> all, java.util.Set<AgentId> seen) {
        var children = new ArrayList<RosterNode>();
        if (seen.add(entry.id())) {
            for (var other : all) {
                if (entry.id().equals(other.parent())) children.add(flat(RosterKind.SUBAGENT, other, all, seen));
            }
        }
        return new RosterNode(kind, entry.id().value(), entry.name(), entry.state(), children);
    }

    /** The visible rows: a collapsed node hides its descendants. */
    private List<Row> rows() {
        var out = new ArrayList<Row>();
        for (var node : nodes()) addRows(node, 0, out);
        return out;
    }

    private void addRows(RosterNode node, int depth, List<Row> out) {
        out.add(new Row(node, depth));
        if (collapsed.contains(node.id())) return;
        for (var child : node.children()) addRows(child, depth + 1, out);
    }

    /** The index of the selected row. The selected id wins over the stored index when the tree changed. */
    private int index(List<Row> rows) {
        if (rows.isEmpty()) return 0;
        for (var i = 0; i < rows.size(); i++) {
            if (rows.get(i).node().id().equals(selectedId)) return i;
        }
        return Math.clamp(selected, 0, rows.size() - 1);
    }

    public void open() {
        open = true;
        var rows = rows();
        selected = 0;
        for (var i = 0; i < rows.size(); i++) if (rows.get(i).node().id().equals(foreground.value())) selected = i;
        selectedId = rows.isEmpty() ? "" : rows.get(selected).node().id();
        syncScroll();
        ensureVisible();
    }

    public void close() { open = false; }

    public AgentId foreground() { return foreground; }

    public int selectedIndex() { return index(rows()); }

    public int scrollOffset() { return scroll.offset(); }

    public ScrollModel scroll() { return scroll; }

    /** The selected node, or null when the tree is empty. */
    public RosterNode selectedNode() {
        var rows = rows();
        return rows.isEmpty() ? null : rows.get(index(rows)).node();
    }

    // ---- keys ----

    public void up() { select(selectedIndex() - 1); }

    public void down() { select(selectedIndex() + 1); }

    private void select(int index) {
        var rows = rows();
        if (rows.isEmpty()) return;
        selected = Math.clamp(index, 0, rows.size() - 1);
        selectedId = rows.get(selected).node().id();
        syncScroll();
        ensureVisible();
    }

    /** Collapses the selected node. Its descendants are hidden. */
    public void collapse() {
        var node = selectedNode();
        if (node != null && node.hasChildren()) {
            collapsed.add(node.id());
            select(selectedIndex());
        }
    }

    /** Expands the selected node. */
    public void expand() {
        var node = selectedNode();
        if (node != null) {
            collapsed.remove(node.id());
            syncScroll();
        }
    }

    /** True when the node with this id is collapsed. */
    public boolean isCollapsed(String nodeId) { return collapsed.contains(nodeId); }

    /**
     * Handles a key name: UP, DOWN, LEFT, RIGHT, ENTER, ESC. Returns true when the key was used. Enter on a
     * harness does nothing.
     */
    public boolean handleKey(String key) {
        if (!open) return false;
        switch (key) {
            case "UP" -> up();
            case "DOWN" -> down();
            case "LEFT" -> collapse();
            case "RIGHT" -> expand();
            case "ENTER" -> {
                var node = selectedNode();
                if (node == null || node.kind() == RosterKind.HARNESS) return true;
                switchTo(new AgentId(node.id()));
                close();
            }
            case "ESC" -> close();
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---- foreground ----

    /** Makes an agent the foreground agent. The view shows its history. */
    public void switchTo(AgentId agent) {
        if (agent.equals(foreground)) return;
        trail.push(foreground);
        foreground = agent;
        reloadView();
        onForeground.accept(agent);
    }

    /** Returns to the parent of the foreground agent. Without a parent, returns to the previous agent. */
    public void back() {
        AgentId parent = null;
        for (var e : directory.agents()) if (e.id().equals(foreground)) parent = e.parent();
        AgentId target = null;
        if (parent != null) {
            trail.remove(parent);
            target = parent;
        } else if (!trail.isEmpty()) {
            target = trail.pop();
        }
        if (target == null) return;
        foreground = target;
        reloadView();
        onForeground.accept(target);
    }

    /** The lines of the foreground chat: the full history, then new output. */
    public List<String> view() { return List.copyOf(view); }

    /** Adds a new output line of an agent. A line of another agent is not part of the view. */
    public void onOutput(AgentId agent, String line) {
        if (agent.equals(foreground)) view.add(line);
    }

    /** Sends typed text to the foreground agent as steering. */
    public void steer(String text) { directory.steer(foreground, text); }

    private void reloadView() {
        view.clear();
        view.addAll(directory.history(foreground));
    }

    // ---- drawing ----

    /**
     * The text rows for the visible part of the tree. The selected row starts with {@code >}. Each level adds
     * two spaces. A node with children shows {@code -} (open) or {@code +} (collapsed).
     */
    public List<String> render() {
        var rows = rows();
        var chosen = index(rows);
        var out = new ArrayList<String>();
        var from = scroll.offset();
        var to = Math.min(rows.size(), from + Math.max(1, bounds.height()));
        for (var i = from; i < to; i++) {
            var row = rows.get(i);
            var node = row.node();
            var line = new StringBuilder(i == chosen ? "> " : "  ");
            line.append("  ".repeat(row.depth()));
            line.append(node.hasChildren() ? (collapsed.contains(node.id()) ? "+ " : "- ") : "  ");
            line.append(node.label());
            if (!node.detail().isBlank()) line.append(" | ").append(node.detail());
            out.add(line.toString());
        }
        return out;
    }

    // ---- mouse ----

    @Override
    public void onMouse(RegionEvent event) {
        syncScroll();
        if (event.isWheel()) {
            scroll.wheel(event.button(), event.shift());
        } else if (event.type() == MouseEvent.Type.Pressed && event.button() == MouseEvent.Button.Button1) {
            var index = scroll.offset() + event.y();
            var rows = rows();
            if (event.y() < 0 || index >= rows.size()) return;
            select(index);
            if (event.clicks() >= 2) handleKey("ENTER");
        }
    }

    private void syncScroll() { scroll.setContent(rows().size(), bounds.height()); }

    private void ensureVisible() {
        var at = selectedIndex();
        if (at < scroll.offset()) scroll.shift(at - scroll.offset());
        else if (at >= scroll.offset() + bounds.height()) {
            scroll.shift(at - (scroll.offset() + bounds.height() - 1));
        }
    }
}
