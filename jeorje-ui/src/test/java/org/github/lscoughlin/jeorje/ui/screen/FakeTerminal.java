package org.github.lscoughlin.jeorje.ui.screen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import org.jline.terminal.Size;
import org.jline.terminal.impl.ExternalTerminal;

/** A terminal for tests. Tests write scripted input with {@link #type} and read the output with {@link #output}. */
public final class FakeTerminal implements AutoCloseable {
    public final ExternalTerminal terminal;
    private final PipedOutputStream keys = new PipedOutputStream();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    public FakeTerminal(int columns, int rows) throws IOException {
        var in = new PipedInputStream(keys, 8192);
        terminal = new ExternalTerminal("test", "xterm", in, out, StandardCharsets.UTF_8);
        terminal.setSize(new Size(columns, rows));
    }

    public void type(String text) throws IOException {
        keys.write(text.getBytes(StandardCharsets.UTF_8));
        keys.flush();
    }

    public String output() {
        terminal.flush();
        return out.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        terminal.close();
    }
}
