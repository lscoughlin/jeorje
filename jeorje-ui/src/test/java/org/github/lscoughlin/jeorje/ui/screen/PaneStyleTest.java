package org.github.lscoughlin.jeorje.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;
import org.github.lscoughlin.jeorje.ui.mouse.RegionEvent;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

/** The rows of a pane keep their style while the screen draws them. */
class PaneStyleTest {

    /** A pane with one plain row and one inverse row that is wider than the pane. */
    private static final class PaneStub implements Pane<String> {
        private final boolean styled;
        private Rect bounds;
        private Runnable onKey = () -> { };
        private boolean done;

        PaneStub(boolean styled) {
            this.styled = styled;
        }

        @Override
        public String title() { return "Title"; }

        @Override
        public void resize(Rect bounds) { this.bounds = bounds; }

        @Override
        public List<String> render() { return List.of("plain", "x".repeat(40)); }

        @Override
        public List<AttributedString> renderStyled() {
            return styled
                    ? List.of(new AttributedString("plain"),
                            new AttributedString("x".repeat(40), AttributedStyle.DEFAULT.inverse()))
                    : Pane.super.renderStyled();
        }

        /** Runs while the pane is open, after the screen drew it. */
        void whileOpen(Runnable action) {
            onKey = () -> {
                action.run();
                done = true;
            };
        }

        @Override
        public void key(String key) { onKey.run(); }

        @Override
        public void mouse(RegionEvent event) {}

        @Override
        public boolean done() { return done; }

        @Override
        public Optional<String> result() { return Optional.empty(); }

        Rect bounds() { return bounds; }
    }

    private static Screen screen(FakeTerminal ft) {
        return new Screen(ft.terminal, Screen.Options.defaults());
    }

    private static void open(Screen screen, PaneStub pane, FakeTerminal ft) throws Exception {
        screen.draw(); // the first draw probes the terminal modes and reads the reply
        ft.type(" ");
        screen.runPane(pane);
    }

    @Test
    void theDefaultStyledRenderWrapsTheTextRows() {
        var pane = new PaneStub(false);
        pane.resize(new Rect(0, 1, 20, 6));
        var rows = pane.renderStyled();
        assertEquals(pane.render(), rows.stream().map(AttributedString::toString).toList());
        for (var row : rows) {
            for (var i = 0; i < row.length(); i++) {
                assertEquals(AttributedStyle.DEFAULT, row.styleAt(i), "a default row is plain");
            }
        }
    }

    @Test
    void theScreenKeepsTheStyleOfARowAndCutsItToTheColumns() throws Exception {
        try (var ft = new FakeTerminal(20, 10)) {
            var screen = screen(ft);
            var pane = new PaneStub(true);
            var captured = new ArrayList<List<AttributedString>>();
            pane.whileOpen(() -> captured.add(screen.renderRegion(10, 20)));
            open(screen, pane, ft);
            assertEquals(1, captured.size(), "the pane rendered its rows while it was open");
            var rows = captured.getFirst();
            assertEquals(20, pane.bounds().width(), "the pane got the region width");
            assertEquals(AttributedStyle.DEFAULT.inverse(), rows.getFirst().styleAt(0), "the title is inverse");
            for (var i = 0; i < rows.get(1).length(); i++) {
                assertEquals(AttributedStyle.DEFAULT, rows.get(1).styleAt(i), "row 1 is plain");
            }
            assertEquals(20, rows.get(2).columnLength(), "the styled row is cut to the columns");
            for (var i = 0; i < rows.get(2).length(); i++) {
                assertEquals(AttributedStyle.DEFAULT.inverse(), rows.get(2).styleAt(i), "the style survives the cut");
            }
        }
    }

    @Test
    void aDefaultPaneRowIsPlainOnTheScreen() throws Exception {
        try (var ft = new FakeTerminal(20, 10)) {
            var screen = screen(ft);
            var pane = new PaneStub(false);
            var captured = new ArrayList<List<AttributedString>>();
            pane.whileOpen(() -> captured.add(screen.renderRegion(10, 20)));
            open(screen, pane, ft);
            var rows = captured.getFirst();
            for (var i = 0; i < rows.get(2).length(); i++) {
                assertEquals(AttributedStyle.DEFAULT, rows.get(2).styleAt(i), "a default pane row is plain");
            }
            assertTrue(rows.get(2).toString().startsWith("xxx"), rows.get(2).toString());
        }
    }
}
