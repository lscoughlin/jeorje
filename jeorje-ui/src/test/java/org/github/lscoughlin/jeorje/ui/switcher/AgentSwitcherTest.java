package org.github.lscoughlin.jeorje.ui.switcher;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.github.lscoughlin.jeorje.core.directory.AgentEntry;
import org.github.lscoughlin.jeorje.core.directory.InMemoryAgentDirectory;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;
import org.github.lscoughlin.jeorje.ui.mouse.RegionEvent;
import org.jline.terminal.MouseEvent.Button;
import org.jline.terminal.MouseEvent.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentSwitcherTest {
    private static final AgentId ROOT = new AgentId("root");
    private static final AgentId A = new AgentId("a");
    private static final AgentId B = new AgentId("b");
    private InMemoryAgentDirectory dir;
    private AgentSwitcher sw;

    @BeforeEach
    void setUp() {
        dir = new InMemoryAgentDirectory()
                .add(new AgentEntry(ROOT, "root", null, "RUNNING", null, "main", "r-last"))
                .add(new AgentEntry(A, "alpha", ROOT, "RUNNING", null, "feat-a", ""))
                .add(new AgentEntry(B, "beta", A, "IDLE", "peer9", null, ""));
        dir.appendOutput(A, "a1");
        dir.appendOutput(A, "a2");
        sw = new AgentSwitcher(dir, ROOT, new Rect(0, 0, 80, 2), 1);
    }

    private static RegionEvent wheel(Button b) {
        return new RegionEvent(Type.Wheel, b, Set.of(), 0, 0, 0);
    }

    private static RegionEvent click(int y, int clicks) {
        return new RegionEvent(Type.Pressed, Button.Button1, Set.of(), 1, y, clicks);
    }

    @Test
    void rowsShowNameParentStatePeerBranchAndLastOutput() {
        sw.open();
        var rows = sw.render();
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).startsWith("> root"));
        assertTrue(rows.get(1).contains("alpha | root | RUNNING | local | feat-a | a2"));
    }

    @Test
    void enterSwitchesAndShowsHistoryThenNewOutput() {
        sw.open();
        sw.handleKey("DOWN");
        sw.handleKey("ENTER");
        assertFalse(sw.isOpen());
        assertEquals(A, sw.foreground());
        assertEquals(java.util.List.of("a1", "a2"), sw.view());
        dir.appendOutput(A, "a3");
        sw.onOutput(A, "a3");
        sw.onOutput(B, "ignored");
        assertEquals(java.util.List.of("a1", "a2", "a3"), sw.view());
    }

    @Test
    void escClosesWithoutSwitching() {
        sw.open();
        sw.handleKey("DOWN");
        sw.handleKey("ESC");
        assertFalse(sw.isOpen());
        assertEquals(ROOT, sw.foreground());
    }

    @Test
    void backReturnsToParent() {
        sw.switchTo(B);
        assertEquals(B, sw.foreground());
        sw.back();
        assertEquals(A, sw.foreground());
        sw.back();
        assertEquals(ROOT, sw.foreground());
        sw.back();
        assertEquals(ROOT, sw.foreground());
    }

    @Test
    void steeringGoesToForegroundAgent() {
        sw.steer("to root");
        sw.switchTo(A);
        sw.steer("to alpha");
        var sent = dir.steeringSent();
        assertEquals(ROOT, sent.get(0).agent());
        assertEquals(A, sent.get(1).agent());
        assertEquals("to alpha", sent.get(1).text());
    }

    @Test
    void wheelScrollsListWithoutChangingSelection() {
        sw.open();
        assertEquals(0, sw.scrollOffset());
        sw.onMouse(wheel(Button.WheelDown));
        assertEquals(1, sw.scrollOffset());
        assertEquals(0, sw.selectedIndex());
        sw.onMouse(wheel(Button.WheelUp));
        assertEquals(0, sw.scrollOffset());
    }

    @Test
    void keyboardSelectionScrollsIntoView() {
        sw.open();
        sw.down();
        sw.down();
        assertEquals(2, sw.selectedIndex());
        assertEquals(1, sw.scrollOffset());
    }

    @Test
    void clickSelectsAndDoubleClickSwitches() {
        sw.open();
        sw.onMouse(click(1, 1));
        assertEquals(1, sw.selectedIndex());
        assertEquals(ROOT, sw.foreground());
        sw.onMouse(click(1, 2));
        assertEquals(A, sw.foreground());
    }
}
