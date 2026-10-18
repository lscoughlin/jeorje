package org.github.lscoughlin.jeorje.ui.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.github.lscoughlin.jeorje.core.state.FileState;
import org.github.lscoughlin.jeorje.ui.keys.KeyBindings;
import org.github.lscoughlin.jeorje.ui.keys.KeyLayer;
import org.jline.terminal.MouseEvent;

/** A editor on a scripted screen of 80 by 24 cells, with a temporary directory. */
final class EditorFixture {
    static final String UP = "\u001b[A";
    static final String DOWN = "\u001b[B";
    static final String RIGHT = "\u001b[C";
    static final String LEFT = "\u001b[D";
    static final String HOME = "\u001b[H";
    static final String END = "\u001b[F";
    static final String DEL = "\u001b[3~";
    static final String INS = "\u001b[2~";
    static final String PGUP = "\u001b[5~";
    static final String PGDN = "\u001b[6~";
    static final String F1 = "\u001bOP";
    static final String F3 = "\u001bOR";
    static final String F6 = "\u001b[17~";
    static final String F10 = "\u001b[21~";
    static final String ENTER = "\r";
    static final String ESC = "\u001b";
    static final String TAB = "\t";
    static final String SHIFT_TAB = "\u001b[Z";
    static final String BACKSPACE = "\u007f";
    static final String CTRL_S = "\u0013";
    static final String CTRL_Z = "\u001a";
    static final String CTRL_Y = "\u0019";
    static final String CTRL_G = "\u0007";
    static final String CTRL_A = "\u0001";
    static final String CTRL_Q = "\u0011";
    static final String CTRL_C = "\u0003";
    static final String CTRL_V = "\u0016";
    static final String CTRL_X = "\u0018";
    static final String SHIFT_DEL = "\u001b[3;2~";
    static final String CTRL_INS = "\u001b[2;5~";
    static final String SHIFT_INS = "\u001b[2;2~";
    static final String SHIFT_LEFT = "\u001b[1;2D";
    static final String SHIFT_RIGHT = "\u001b[1;2C";
    static final String SHIFT_UP = "\u001b[1;2A";
    static final String SHIFT_DOWN = "\u001b[1;2B";
    static final String SHIFT_END = "\u001b[1;2F";
    static final String CTRL_LEFT = "\u001b[1;5D";
    static final String CTRL_RIGHT = "\u001b[1;5C";
    static final String CTRL_HOME = "\u001b[1;5H";
    static final String CTRL_END = "\u001b[1;5F";
    static final String ALT_X = "\u001bx";
    static final String ALT_F = "\u001bf";
    static final String ALT_E = "\u001be";
    static final String ALT_V = "\u001bv";

    final Path dir;
    final AtomicLong clock = new AtomicLong(1_000_000);
    final EditorClipboard clipboard = EditorClipboard.memory();
    final List<String> saved = new ArrayList<>();
    final List<FileState> savedStates = new ArrayList<>();
    final QEditor editor;

    EditorFixture(Path dir) {
        this(dir, KeyBindings.defaults(), EditorOptions.defaults().withNoColor(true));
    }

    EditorFixture(Path dir, KeyBindings keys, EditorOptions options) {
        this(dir, keys, options, null, null);
    }

    EditorFixture(Path dir, KeyBindings keys, EditorOptions options,
                  org.github.lscoughlin.jeorje.ui.syntax.SyntaxCatalog catalog,
                  org.github.lscoughlin.jeorje.ui.syntax.HighlightService highlight) {
        this.dir = dir;
        this.editor = QEditor.builder()
                .keys(keys)
                .options(options)
                .clipboard(clipboard)
                .catalog(catalog)
                .highlight(highlight)
                .baseDir(dir)
                .clock(clock::get)
                .fileStates((path, state) -> {
                    saved.add(path.toString());
                    savedStates.add(state);
                })
                .build();
        editor.setSize(80, 24);
    }

    static KeyBindings keysWith(String... pairs) {
        var map = new java.util.LinkedHashMap<String, Object>();
        for (var i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return KeyBindings.resolve(List.of(new KeyLayer("test", java.util.Map.of(), map)));
    }

    EditorFixture type(String keys) {
        editor.feed(keys);
        return this;
    }

    Path write(String name, String content) throws IOException {
        var path = dir.resolve(name);
        Files.writeString(path, content);
        return path;
    }

    Path writeBytes(String name, byte[] content) throws IOException {
        var path = dir.resolve(name);
        Files.write(path, content);
        return path;
    }

    String read(String name) throws IOException {
        return new String(Files.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8);
    }

    List<String> screen() {
        return editor.screenText();
    }

    String screenJoined() {
        return String.join("\n", screen());
    }

    boolean screenHas(String text) {
        return screenJoined().contains(text);
    }

    String status() {
        return editor.statusText();
    }

    // ---- mouse ----

    void mouse(MouseEvent.Type type, MouseEvent.Button button, int x, int y, MouseEvent.Modifier... mods) {
        var set = EnumSet.noneOf(MouseEvent.Modifier.class);
        set.addAll(List.of(mods));
        editor.mouse(new MouseEvent(type, button, set, x, y));
    }

    void click(int x, int y) {
        clock.addAndGet(1000);
        mouse(MouseEvent.Type.Pressed, MouseEvent.Button.Button1, x, y);
        mouse(MouseEvent.Type.Released, MouseEvent.Button.Button1, x, y);
    }

    /** A click that counts as a second click on the same cell. */
    void clickAgain(int x, int y) {
        clock.addAndGet(100);
        mouse(MouseEvent.Type.Pressed, MouseEvent.Button.Button1, x, y);
        mouse(MouseEvent.Type.Released, MouseEvent.Button.Button1, x, y);
    }

    void drag(int x1, int y1, int x2, int y2) {
        clock.addAndGet(1000);
        mouse(MouseEvent.Type.Pressed, MouseEvent.Button.Button1, x1, y1);
        mouse(MouseEvent.Type.Dragged, MouseEvent.Button.Button1, x2, y2);
        mouse(MouseEvent.Type.Released, MouseEvent.Button.Button1, x2, y2);
    }

    void wheel(int x, int y, boolean up, MouseEvent.Modifier... mods) {
        mouse(MouseEvent.Type.Wheel, up ? MouseEvent.Button.WheelUp : MouseEvent.Button.WheelDown, x, y, mods);
    }

    static String lines(int count) {
        var sb = new StringBuilder();
        for (var i = 1; i <= count; i++) {
            sb.append("line ").append(i).append('\n');
        }
        return sb.toString();
    }
}
