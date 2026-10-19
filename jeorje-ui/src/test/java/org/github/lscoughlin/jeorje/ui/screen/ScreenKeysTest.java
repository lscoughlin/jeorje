package org.github.lscoughlin.jeorje.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.github.lscoughlin.jeorje.ui.keys.KeyBindings;
import org.github.lscoughlin.jeorje.ui.keys.KeyLayer;
import org.github.lscoughlin.jeorje.ui.keys.KeySpec;
import org.junit.jupiter.api.Test;

class ScreenKeysTest {

    private static void await(java.util.function.BooleanSupplier c) throws InterruptedException {
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!c.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(c.getAsBoolean(), "condition not reached in time");
    }

    private static final class Recorder implements ActionHandler {
        final List<String> actions = new CopyOnWriteArrayList<>();
        final AtomicBoolean exited = new AtomicBoolean();

        @Override
        public void handle(String actionId) { actions.add(actionId); }

        @Override
        public void exit() { exited.set(true); }
    }

    private static String seq(String key) {
        return KeySpec.parse(key).sequences(false).getFirst();
    }

    @Test
    void everyDefaultChordReachesTheHandler() throws Exception {
        try (var ft = new FakeTerminal(60, 12)) {
            var rec = new Recorder();
            var screen = new Screen(ft.terminal, Screen.Options.defaults().actionHandler(rec));
            screen.start();
            try {
                var chords = new java.util.LinkedHashMap<String, String>();
                chords.put("agents", seq("Ctrl-X a"));
                chords.put("files", seq("Ctrl-X f"));
                chords.put("expandResult", seq("Ctrl-X o"));
                chords.put("follow", seq("Ctrl-X b"));
                chords.put("copySelection", seq("Ctrl-X c"));
                chords.put("scrollUp", seq("PgUp"));
                chords.put("scrollDown", seq("PgDn"));
                chords.put("redraw", seq("Ctrl-L"));
                chords.put("newline", seq("Alt-Enter"));
                var count = 0;
                for (var e : chords.entrySet()) {
                    ft.type(e.getValue());
                    count++;
                    var expected = count;
                    await(() -> rec.actions.size() >= expected);
                    assertEquals(e.getKey(), rec.actions.get(expected - 1), "chord of " + e.getKey());
                }
                ft.type("\r");
                await(() -> rec.actions.contains("send"));
                ft.type("\u0003");
                await(() -> rec.actions.contains("interrupt"));
            } finally {
                screen.close();
            }
        }
    }

    @Test
    void aConfiguredKeyReplacesTheDefault() throws Exception {
        try (var ft = new FakeTerminal(60, 12)) {
            var rec = new Recorder();
            var bindings = KeyBindings.resolve(List.of(new KeyLayer("test", Map.of("agents", "Ctrl-X g"), Map.of())));
            var screen = new Screen(ft.terminal,
                    Screen.Options.defaults().keyBindings(bindings).actionHandler(rec));
            screen.start();
            try {
                ft.type("\u0018a");
                ft.type("\u0018g");
                ft.type("\u0018f");
                await(() -> rec.actions.size() >= 2);
                assertEquals(List.of("agents", "files"), rec.actions);
            } finally {
                screen.close();
            }
        }
    }

    @Test
    void anActionOnAnEditingKeyWinsWithAWarning() throws Exception {
        var bindings = KeyBindings.resolve(List.of(new KeyLayer("test", Map.of("agents", "Ctrl-A"), Map.of())));
        assertTrue(bindings.warnings().stream().anyMatch(w -> w.contains("beginning-of-line")));
        try (var ft = new FakeTerminal(60, 12)) {
            var rec = new Recorder();
            var screen = new Screen(ft.terminal,
                    Screen.Options.defaults().keyBindings(bindings).actionHandler(rec));
            screen.start();
            try {
                ft.type("\u0001");
                await(() -> rec.actions.contains("agents"));
            } finally {
                screen.close();
            }
        }
    }

    @Test
    void ctrlDInterruptsAndNeverEndsTheSession() throws Exception {
        try (var ft = new FakeTerminal(60, 12)) {
            var rec = new Recorder();
            var opts = new Screen.Options("off", null, null, null, null).actionHandler(rec);
            var screen = new Screen(ft.terminal, opts);
            screen.start();
            try {
                ft.type("\u0004");
                await(() -> rec.actions.contains("interrupt"));
                assertFalse(rec.exited.get());
                assertTrue(screen.isRunning());
            } finally {
                screen.close();
            }
        }
    }

    @Test
    void theInputRowGrowsAndShrinks() throws Exception {
        try (var ft = new FakeTerminal(60, 12)) {
            var lines = new CopyOnWriteArrayList<String>();
            var screen = new Screen(ft.terminal, new Screen.Options("off", null, lines::add, null, null));
            screen.start();
            try {
                assertEquals(1, screen.inputRows());
                assertEquals(10, screen.renderRegion(12, 60).size());
                ft.type("a" + seq("Alt-Enter") + "b" + seq("Alt-Enter") + "c");
                await(() -> screen.inputRows() == 3);
                assertEquals(8, screen.renderRegion(12, 60).size());
                var rows = screen.renderScreen(12, 60);
                assertEquals(12, rows.size());
                assertEquals("> a", rows.get(8).toString().stripTrailing());
                assertEquals("c", rows.get(10).toString().stripTrailing());
                assertTrue(rows.get(11).toString().contains("main"));
                ft.type("\u007f\u007f");
                await(() -> screen.inputRows() == 2);
                assertEquals(9, screen.renderRegion(12, 60).size());
                ft.type("\r");
                await(() -> lines.size() == 1);
                assertEquals("a\nb", lines.getFirst());
                await(() -> screen.inputRows() == 1);
                assertEquals(10, screen.renderRegion(12, 60).size());
            } finally {
                screen.close();
            }
        }
    }

    @Test
    void historyWorksWithTheUpAndDownArrows() throws Exception {
        try (var ft = new FakeTerminal(60, 12)) {
            var lines = new CopyOnWriteArrayList<String>();
            var screen = new Screen(ft.terminal, new Screen.Options("off", null, lines::add, null, null));
            screen.start();
            try {
                ft.type("one\r");
                await(() -> lines.size() == 1);
                ft.type("two\r");
                await(() -> lines.size() == 2);
                // up, up, down, then send: the line is "two"
                ft.type("\u001bOA\u001bOA\u001bOB\r");
                await(() -> lines.size() == 3);
                assertEquals("two", lines.get(2));
                // up, up, up (stays on the oldest), down, down, down (back to the empty line), then text
                ft.type("\u001bOA\u001bOA\u001bOA\u001bOB\u001bOB\u001bOB" + "x\r");
                await(() -> lines.size() == 4);
                assertEquals("x", lines.get(3));
            } finally {
                screen.close();
            }
        }
    }
}
