package org.github.lscoughlin.jeorje.ui.switcher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.core.directory.AgentDirectory;
import org.github.lscoughlin.jeorje.core.directory.AgentEntry;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;
import org.github.lscoughlin.jeorje.ui.mouse.Region;
import org.github.lscoughlin.jeorje.ui.mouse.RegionEvent;
import org.github.lscoughlin.jeorje.ui.mouse.ScrollModel;
import org.jline.terminal.MouseEvent;

/**
 * The list of agents and peers, and the foreground agent. The list is a mouse region. The wheel scrolls the
 * list and does not change the selected row. A click selects a row. Enter makes the selected agent the
 * foreground agent. The foreground view shows the full history of the agent and then new output.
 */
public final class AgentSwitcher implements Region {
    private final AgentDirectory directory;
    private final ScrollModel scroll;
    private final Deque<AgentId> trail = new ArrayDeque<>();
    private Rect bounds;
    private int selected;
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

    public void open() {
        open = true;
        var rows = directory.agents();
        selected = 0;
        for (var i = 0; i < rows.size(); i++) if (rows.get(i).id().equals(foreground)) selected = i;
        syncScroll();
        ensureVisible();
    }

    public void close() { open = false; }

    public AgentId foreground() { return foreground; }

    public int selectedIndex() { return selected; }

    public int scrollOffset() { return scroll.offset(); }

    public ScrollModel scroll() { return scroll; }

    public AgentEntry selectedEntry() {
        var rows = directory.agents();
        return rows.isEmpty() ? null : rows.get(Math.clamp(selected, 0, rows.size() - 1));
    }

    // ---- keys ----

    public void up() { select(selected - 1); }

    public void down() { select(selected + 1); }

    private void select(int index) {
        var rows = directory.agents();
        if (rows.isEmpty()) return;
        selected = Math.clamp(index, 0, rows.size() - 1);
        syncScroll();
        ensureVisible();
    }

    /** Handles a key name: UP, DOWN, ENTER, ESC. Returns true when the key was used. */
    public boolean handleKey(String key) {
        if (!open) return false;
        switch (key) {
            case "UP" -> up();
            case "DOWN" -> down();
            case "ENTER" -> {
                var entry = selectedEntry();
                if (entry != null) switchTo(entry.id());
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

    /** The text rows for the visible part of the list. The selected row starts with {@code >}. */
    public List<String> render() {
        var rows = directory.agents();
        var out = new ArrayList<String>();
        var from = scroll.offset();
        var to = Math.min(rows.size(), from + Math.max(1, bounds.height()));
        for (var i = from; i < to; i++) {
            var e = rows.get(i);
            var line = new StringBuilder(i == selected ? "> " : "  ");
            line.append(e.name()).append(" | ").append(e.parent() == null ? "-" : e.parent().value());
            line.append(" | ").append(e.state());
            line.append(" | ").append(e.peer() == null ? "local" : e.peer());
            if (e.branch() != null) line.append(" | ").append(e.branch());
            line.append(" | ").append(e.lastOutput());
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
            var rows = directory.agents();
            if (event.y() < 0 || index >= rows.size()) return;
            selected = index;
            if (event.clicks() >= 2) handleKey("ENTER");
        }
    }

    private void syncScroll() { scroll.setContent(directory.agents().size(), bounds.height()); }

    private void ensureVisible() {
        if (selected < scroll.offset()) scroll.shift(selected - scroll.offset());
        else if (selected >= scroll.offset() + bounds.height()) {
            scroll.shift(selected - (scroll.offset() + bounds.height() - 1));
        }
    }
}
