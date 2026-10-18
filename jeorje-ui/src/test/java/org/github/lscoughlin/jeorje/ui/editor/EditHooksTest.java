package org.github.lscoughlin.jeorje.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.ExternalTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs {@link QEditor#edit} on a JLine terminal with scripted input. */
class EditHooksTest {

    @TempDir
    Path dir;

    private final PipedOutputStream keys = new PipedOutputStream();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private ExternalTerminal terminal;

    private void startTerminal() throws IOException {
        var in = new PipedInputStream(keys, 1 << 16);
        terminal = new ExternalTerminal("test", "xterm", in, out, StandardCharsets.UTF_8);
        terminal.setSize(new Size(60, 12));
    }

    @AfterEach
    void close() throws IOException {
        if (terminal != null) {
            terminal.close();
        }
    }

    private void type(String text) throws IOException {
        keys.write(text.getBytes(StandardCharsets.UTF_8));
        keys.flush();
    }

    private String output() {
        terminal.flush();
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(20);
        }
    }

    private Thread start(Path file, EditorHooks hooks, AtomicReference<Throwable> failure) {
        var thread = new Thread(() -> {
            try {
                QEditor.edit(terminal, file, hooks);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "edit-test");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private record Flags(int input, int output, int local) {
        static Flags of(Terminal t) {
            var a = t.getAttributes();
            return new Flags(a.getInputFlags().hashCode(), a.getOutputFlags().hashCode(),
                    a.getLocalFlags().hashCode());
        }
    }

    @Test
    void editReturnsAfterAltXAndRestoresTrackingAndAttributes() throws Exception {
        startTerminal();
        var file = dir.resolve("a.txt");
        Files.writeString(file, "start\n");
        terminal.trackMouse(Terminal.MouseTracking.Button);
        var before = Flags.of(terminal);
        var saved = new ArrayList<Path>();
        var hooks = EditorHooks.defaults().withFileStates((path, state) -> saved.add(path));
        var failure = new AtomicReference<Throwable>();
        var thread = start(file, hooks, failure);
        waitFor(() -> output().contains("start"));
        type("hello ");
        type("\u0013"); // Ctrl-S
        type("\u001bx"); // Alt-X
        thread.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(thread.isAlive(), "edit must return");
        assertEquals(null, failure.get());
        assertEquals("hello start\n", Files.readString(file));
        assertEquals(1, saved.size());
        assertEquals(Terminal.MouseTracking.Button, terminal.getCurrentMouseTracking(), "tracking is on again");
        assertEquals(before, Flags.of(terminal), "terminal attributes are as before");
        var output = output();
        assertTrue(output.contains("\u001b[?1049h"), "the editor enters the alternate screen");
        assertTrue(output.contains("\u001b[?1049l"), "the editor leaves the alternate screen");
    }

    @Test
    void trackingStaysOffWhenItWasOff() throws Exception {
        startTerminal();
        var file = dir.resolve("b.txt");
        Files.writeString(file, "text\n");
        var failure = new AtomicReference<Throwable>();
        var thread = start(file, EditorHooks.defaults(), failure);
        waitFor(() -> output().contains("text"));
        type("\u001bx");
        thread.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(thread.isAlive());
        assertEquals(null, failure.get());
        assertEquals(Terminal.MouseTracking.Off, terminal.getCurrentMouseTracking());
    }

    @Test
    void anActiveAlternateScreenIsNotLeft() throws Exception {
        startTerminal();
        var file = dir.resolve("c.txt");
        Files.writeString(file, "text\n");
        var failure = new AtomicReference<Throwable>();
        var thread = start(file, EditorHooks.defaults().withAlternateScreenActive(true), failure);
        waitFor(() -> output().contains("text"));
        type("\u001bx");
        thread.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(thread.isAlive());
        assertEquals(null, failure.get());
        assertFalse(output().contains("\u001b[?1049l"), "the caller keeps the alternate screen");
        assertFalse(output().contains("\u001b[?1049h"));
    }

    @Test
    void agentChangeCallbackReceivesTheNotice() throws Exception {
        startTerminal();
        var file = dir.resolve("d.txt");
        Files.writeString(file, "text\n");
        var seen = new ArrayList<String>();
        var hooks = EditorHooks.defaults().withAgentChangeNotice(seen::add);
        // edit() builds its own editor, so this test drives the same wiring through the builder.
        var editor = QEditor.builder().baseDir(dir).noticeSink(hooks.agentChangeNotice()).build();
        editor.open(file);
        editor.externalChange(file);
        assertEquals(1, seen.size());
        assertTrue(seen.getFirst().startsWith(QEditor.CHANGED_BY_AGENT));
    }
}
