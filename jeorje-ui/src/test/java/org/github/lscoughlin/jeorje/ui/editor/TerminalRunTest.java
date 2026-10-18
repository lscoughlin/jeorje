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
import java.util.concurrent.TimeUnit;
import org.jline.terminal.Size;
import org.jline.terminal.impl.ExternalTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the editor loop on a JLine terminal with scripted input. */
class TerminalRunTest {

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

    private void type(String text) throws IOException {
        keys.write(text.getBytes(StandardCharsets.UTF_8));
        keys.flush();
    }

    private Thread runEditor(QEditor editor) {
        var thread = new Thread(() -> editor.run(terminal), "editor-test");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @Test
    void typeSaveAndExitOnATerminal() throws Exception {
        startTerminal();
        var file = dir.resolve("a.txt");
        Files.writeString(file, "start\n");
        var editor = QEditor.builder().baseDir(dir).options(EditorOptions.defaults().withNoColor(true)).build();
        editor.open(file);
        var thread = runEditor(editor);
        waitFor(() -> output().contains("start"));
        type("hello ");
        type("\u0013"); // Ctrl-S
        type("\u001bx"); // Alt-X
        thread.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(thread.isAlive(), "the editor loop must end");
        assertEquals("hello start\n", Files.readString(file));
        var output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("start"), "the screen shows the text");
        assertTrue(output.contains("a.txt"));
        terminal.close();
    }

    @Test
    void menusAndDialogsWorkOnATerminal() throws Exception {
        startTerminal();
        var editor = QEditor.builder().baseDir(dir).options(EditorOptions.defaults().withNoColor(true)).build();
        var thread = runEditor(editor);
        type("abc");
        type("\u001b[21~"); // F10
        type("\u001b"); // Esc closes the menu
        type("\u0011"); // Ctrl-Q, then a pause, then f: the chord waits for its second key
        Thread.sleep(150);
        type("f");
        waitFor(editor::dialogOpen);
        assertEquals("Find", editor.dialogTitle());
        type("b\r");
        waitFor(() -> editor.selectedText().equals("b"));
        assertEquals("b", editor.selectedText());
        type("\u001bx");
        waitFor(editor::dialogOpen);
        assertTrue(editor.dialogOpen());
        assertEquals("abc", editor.text());
        // The text is unsaved, so the dialog is still open. Discard.
        type("\t\r");
        thread.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(thread.isAlive());
        terminal.close();
    }

    @Test
    void mouseEventsFromTheTerminalMoveTheCursor() throws Exception {
        startTerminal();
        var file = dir.resolve("m.txt");
        Files.writeString(file, "abcdef\nsecond\n");
        var editor = QEditor.builder().baseDir(dir).options(EditorOptions.defaults().withNoColor(true)).build();
        editor.open(file);
        var thread = runEditor(editor);
        waitFor(() -> output().contains("abcdef"));
        // SGR mouse press and release at column 4, row 3 (1-based): cell (3, 2) is the third char of line 1.
        type("\u001b[<0;4;3M");
        type("\u001b[<0;4;3m");
        waitFor(() -> editor.cursor() == 2);
        assertEquals(2, editor.cursor());
        type("X");
        waitFor(() -> editor.text().startsWith("abXcdef"));
        assertTrue(editor.text().startsWith("abXcdef"));
        type("\u001bx\t\r");
        thread.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(thread.isAlive());
        terminal.close();
    }

    @Test
    void mouseTrackingIsTurnedOffWhenTheEditorEnds() throws Exception {
        startTerminal();
        var editor = QEditor.builder().baseDir(dir).options(EditorOptions.defaults().withNoColor(true)).build();
        var thread = runEditor(editor);
        waitFor(() -> output().contains("\u001b[?1000h") || output().contains("\u001b[?1002h"));
        type("\u001bx");
        thread.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(thread.isAlive());
        var output = output();
        assertTrue(output.contains("\u001b[?1000l") || output.contains("\u001b[?1002l"), "tracking must go off");
        terminal.close();
    }

    private String output() {
        terminal.flush();
        return out.toString(StandardCharsets.UTF_8);
    }

    private void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(20);
        }
    }
}
