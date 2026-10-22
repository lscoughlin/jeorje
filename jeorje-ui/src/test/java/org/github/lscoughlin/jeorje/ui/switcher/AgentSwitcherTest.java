package org.github.lscoughlin.jeorje.ui.switcher;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
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
        sw = new AgentSwitcher(dir, ROOT, new Rect(0, 0, 80, 3), 1);
    }

    private static RegionEvent wheel(Button b) {
        return new RegionEvent(Type.Wheel, b, Set.of(), 0, 0, 0);
    }

    private static RegionEvent click(int y, int clicks) {
        return new RegionEvent(Type.Pressed, Button.Button1, Set.of(), 1, y, clicks);
    }

    @Test
    void rowsShowTheTreeWithTwoSpacesPerLevelAndAnExpandMarker() {
        sw.open();
        var rows = sw.render();
        assertEquals(3, rows.size());
        sw.setBounds(new Rect(0, 0, 80, 10));
        var all = sw.render();
        assertEquals(List.of(
                "  - local",
                ">   - root | RUNNING",
                "      - alpha | RUNNING",
                "          beta | IDLE"), all);
    }

    @Test
    void leftCollapsesTheSelectedNodeAndRightExpandsIt() {
        sw.setBounds(new Rect(0, 0, 80, 10));
        sw.open();
        assertEquals(4, sw.render().size());
        sw.handleKey("LEFT");
        var collapsed = sw.render();
        assertEquals(List.of("  - local", ">   + root | RUNNING"), collapsed);
        assertTrue(sw.isCollapsed("root"));
        sw.handleKey("RIGHT");
        assertEquals(4, sw.render().size());
        assertFalse(sw.isCollapsed("root"));
        // Collapsing a leaf does nothing.
        sw.handleKey("DOWN");
        sw.handleKey("DOWN");
        sw.handleKey("LEFT");
        assertEquals(4, sw.render().size());
        // Collapsing the harness hides everything below it.
        sw.handleKey("UP");
        sw.handleKey("UP");
        sw.handleKey("UP");
        sw.handleKey("LEFT");
        assertEquals(List.of("> + local"), sw.render());
    }

    @Test
    void enterOnAHarnessDoesNothingAndEnterOnAnAgentSwitches() {
        sw.setBounds(new Rect(0, 0, 80, 10));
        sw.open();
        sw.handleKey("UP");
        assertEquals("local", sw.selectedNode().id().replace("harness:", ""));
        sw.handleKey("ENTER");
        assertTrue(sw.isOpen());
        assertEquals(ROOT, sw.foreground());
        sw.handleKey("DOWN");
        sw.handleKey("DOWN");
        sw.handleKey("ENTER");
        assertFalse(sw.isOpen());
        assertEquals(A, sw.foreground());
    }

    @Test
    void aDirectoryWithoutATreeIsShownAsAFlatTree() {
        var plain = new org.github.lscoughlin.jeorje.core.directory.AgentDirectory() {
            @Override public List<AgentEntry> agents() { return dir.agents(); }

            @Override public List<String> history(AgentId agent) { return List.of(); }

            @Override public void steer(AgentId agent, String text) { }
        };
        var flat = new AgentSwitcher(plain, ROOT, new Rect(0, 0, 80, 10), 1);
        flat.open();
        assertEquals(List.of("> - root | RUNNING", "    - alpha | RUNNING", "        beta | IDLE"), flat.render());
    }

    @Test
    void enterSwitchesAndShowsHistoryThenNewOutput() {
        sw.setBounds(new Rect(0, 0, 80, 10));
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
        sw.setBounds(new Rect(0, 0, 80, 10));
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
        assertEquals(1, sw.selectedIndex());
        sw.onMouse(wheel(Button.WheelUp));
        assertEquals(0, sw.scrollOffset());
    }

    @Test
    void keyboardSelectionScrollsIntoView() {
        sw.setBounds(new Rect(0, 0, 80, 2));
        sw.open();
        assertEquals(1, sw.selectedIndex());
        sw.down();
        sw.down();
        assertEquals(3, sw.selectedIndex());
        assertEquals(2, sw.scrollOffset());
    }

    @Test
    void clickSelectsAndDoubleClickSwitches() {
        sw.setBounds(new Rect(0, 0, 80, 10));
        sw.open();
        sw.onMouse(click(2, 1));
        assertEquals(2, sw.selectedIndex());
        assertEquals(ROOT, sw.foreground());
        sw.onMouse(click(2, 2));
        assertEquals(A, sw.foreground());
    }
}
