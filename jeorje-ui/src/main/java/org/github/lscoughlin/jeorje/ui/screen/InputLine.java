package org.github.lscoughlin.jeorje.ui.screen;

import java.nio.file.Path;
import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;

/** The input line. It wraps a JLine {@link LineReader} with history. {@code Alt-Enter} inserts a new line. */
public final class InputLine {
    private final LineReader reader;

    /**
     * @param terminal the terminal
     * @param historyFile the history file, or null for a history in memory only
     */
    public InputLine(Terminal terminal, Path historyFile) {
        var builder = LineReaderBuilder.builder()
                .terminal(terminal)
                .history(new DefaultHistory())
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true);
        if (historyFile != null) builder.variable(LineReader.HISTORY_FILE, historyFile);
        this.reader = builder.build();
        Widget newline = () -> {
            reader.getBuffer().write('\n');
            return true;
        };
        reader.getWidgets().put("jeorje-newline", newline);
        // The JLine widget asks the terminal for the cursor position after a click and waits for the answer. That
        // wait swallows the next mouse events. The chat regions handle the mouse, so the widget only reads the event.
        reader.getWidgets().put(LineReader.MOUSE, () -> {
            reader.readMouseEvent();
            return true;
        });
        var main = reader.getKeyMaps().get(LineReader.MAIN);
        main.bind(new org.jline.reader.Reference("jeorje-newline"), KeyMap.alt('\r'), KeyMap.alt('\n'));
    }

    /**
     * Reads one input. It blocks until the user presses {@code Enter}.
     *
     * @param initial text to put in the buffer, or null
     * @throws UserInterruptException on {@code Ctrl-C} or when another thread interrupts the read
     * @throws EndOfFileException on {@code Ctrl-D} at an empty line
     */
    public String readLine(String prompt, String initial) throws UserInterruptException, EndOfFileException {
        return reader.readLine(prompt, null, (Character) null, initial);
    }

    /** Binds a key sequence to an action. */
    public void bind(String name, String keys, Runnable action) {
        Widget w = () -> {
            action.run();
            return true;
        };
        reader.getWidgets().put(name, w);
        reader.getKeyMaps().get(LineReader.MAIN).bind(new org.jline.reader.Reference(name), keys);
    }

    /** Binds each key sequence to a widget with the given name. A later binding replaces an earlier one. */
    public void bindWidget(String name, java.util.function.BooleanSupplier body, java.util.Collection<String> sequences) {
        reader.getWidgets().put(name, body::getAsBoolean);
        var main = reader.getKeyMaps().get(LineReader.MAIN);
        for (var sequence : sequences) main.bind(new org.jline.reader.Reference(name), sequence);
    }

    /** Writes a new line at the cursor. */
    public void insertNewline() { reader.getBuffer().write('\n'); }

    /** Removes the bindings of the key sequences from the main key map. */
    public void unbind(java.util.Collection<String> sequences) {
        var main = reader.getKeyMaps().get(LineReader.MAIN);
        for (var sequence : sequences) main.unbind(sequence);
    }

    /** Runs a JLine widget by name, for example {@code accept-line}. It works only inside a read. */
    public boolean callWidget(String name) {
        reader.callWidget(name);
        return true;
    }

    /** Runs a hook after each widget. The hook can change the screen layout and then redraw the line. */
    public void afterEachWidget(Runnable hook) {
        for (var entry : new java.util.ArrayList<>(reader.getWidgets().entrySet())) {
            var original = entry.getValue();
            entry.setValue(() -> {
                var result = original.apply();
                hook.run();
                return result;
            });
        }
    }

    /** Resets the display of the line. The next draw starts at the cursor position. */
    public void resetDisplay() {
        try {
            reader.callWidget(LineReader.REDRAW_LINE);
        } catch (IllegalStateException | NullPointerException e) {
            // no read is active
        }
    }

    /** The text before the cursor. */
    public String beforeCursor() {
        var buffer = reader.getBuffer();
        return buffer.substring(0, buffer.cursor());
    }

    /** Replaces the text before the cursor and keeps the text after it. The cursor goes after the new text. */
    public void replaceBeforeCursor(String text) {
        var buffer = reader.getBuffer();
        var tail = buffer.substring(buffer.cursor());
        buffer.clear();
        buffer.write(text + tail);
        buffer.cursor(text.length());
    }

    /** The text in the buffer now. */
    public String buffer() { return reader.getBuffer().toString(); }

    public History history() { return reader.getHistory(); }

    /** Draws the input line again. It does nothing if no read is active. */
    public void redraw() {
        try {
            reader.callWidget(LineReader.REDRAW_LINE);
        } catch (IllegalStateException | NullPointerException e) {
            // no read is active
        }
    }
}
