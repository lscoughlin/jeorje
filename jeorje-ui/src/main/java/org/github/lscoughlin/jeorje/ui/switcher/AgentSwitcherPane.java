package org.github.lscoughlin.jeorje.ui.switcher;

import java.util.List;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;
import org.github.lscoughlin.jeorje.ui.mouse.RegionEvent;
import org.github.lscoughlin.jeorje.ui.screen.Pane;

/**
 * The agent switcher as a pane. Enter, or a double click on a row, makes the selected agent the foreground
 * agent and returns its id. {@code Esc} cancels.
 */
public final class AgentSwitcherPane implements Pane<AgentId> {
    private final AgentSwitcher switcher;
    private boolean cancelled;
    private AgentId picked;

    public AgentSwitcherPane(AgentSwitcher switcher) {
        this.switcher = switcher;
    }

    @Override
    public String title() { return "Agents - Up/Down move, Enter switch, Esc cancel"; }

    @Override
    public void begin() {
        cancelled = false;
        picked = null;
        switcher.open();
    }

    @Override
    public void end() { switcher.close(); }

    @Override
    public void resize(Rect bounds) { switcher.setBounds(bounds); }

    @Override
    public List<String> render() { return switcher.render(); }

    @Override
    public void key(String key) {
        switch (key) {
            case "k" -> switcher.handleKey("UP");
            case "j" -> switcher.handleKey("DOWN");
            case "ESC" -> {
                cancelled = true;
                switcher.handleKey("ESC");
            }
            default -> switcher.handleKey(key);
        }
        finishIfClosed();
    }

    @Override
    public void mouse(RegionEvent event) {
        switcher.onMouse(event);
        finishIfClosed();
    }

    private void finishIfClosed() {
        if (!switcher.isOpen() && !cancelled && picked == null) picked = switcher.foreground();
    }

    @Override
    public boolean done() { return cancelled || picked != null; }

    @Override
    public Optional<AgentId> result() { return Optional.ofNullable(picked); }
}
