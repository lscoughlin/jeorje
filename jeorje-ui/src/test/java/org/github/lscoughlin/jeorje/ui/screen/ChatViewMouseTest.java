package org.github.lscoughlin.jeorje.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.IntStream;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.ui.mouse.ChatMouse;
import org.github.lscoughlin.jeorje.ui.mouse.Clipboard;
import org.github.lscoughlin.jeorje.ui.mouse.MouseConfig;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;
import org.github.lscoughlin.jeorje.ui.mouse.RegionEvent;
import org.jline.terminal.MouseEvent.Button;
import org.jline.terminal.MouseEvent.Modifier;
import org.jline.terminal.MouseEvent.Type;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

class ChatViewMouseTest {
    private static final AgentId A = new AgentId("a");

    private static ChatView filled(int lines) {
        var chat = new ChatView();
        chat.scrollBar(true, 3);
        chat.apply(new AgentEvent.TokenDelta(A, String.join("\n",
                IntStream.rangeClosed(1, lines).mapToObj(i -> "line%02d".formatted(i)).toList())));
        return chat;
    }

    private static String text(List<AttributedString> rows) {
        return rows.stream().map(AttributedString::toString).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void scrollBarTakesTwoColumnsAndHasExactRows() {
        var chat = filled(50);
        var rows = chat.visible(40, 10);
        assertEquals(10, rows.size());
        for (var row : rows) assertEquals(40, row.columnLength());
        assertEquals("▲", rows.get(0).substring(38, 39).toString());
        assertEquals("▼", rows.get(9).substring(38, 39).toString());
        assertEquals(38, chat.geometry()[2]);
    }

    @Test
    void withoutScrollBarLinesKeepTheFullWidth() {
        var chat = new ChatView();
        chat.apply(new AgentEvent.TokenDelta(A, "x".repeat(30)));
        var rows = chat.visible(20, 5);
        assertEquals(2, rows.size());
        assertEquals(20, rows.get(0).length());
        assertFalse(chat.hasScrollBar());
    }

    @Test
    void newLinesWhileScrolledUpKeepTheViewAndGiveANotice() {
        var chat = filled(50);
        chat.visible(40, 10);
        chat.scrollUp(5);
        var before = text(chat.visible(40, 10));
        assertNull(chat.followNotice("Ctrl-X b"));
        chat.appendNotice("n1\nn2\nn3");
        assertEquals(before, text(chat.visible(40, 10)));
        assertEquals(3, chat.newLines());
        assertEquals("3 new lines - Ctrl-X b or click here to follow", chat.followNotice("Ctrl-X b"));
        chat.scrollToEnd();
        assertTrue(chat.following());
        assertNull(chat.followNotice("Ctrl-X b"));
        assertTrue(text(chat.visible(40, 10)).contains("n3"));
    }

    @Test
    void scrollingToTheBottomByWheelClearsTheNotice() {
        var chat = filled(50);
        chat.visible(40, 10);
        chat.scrollUp(2);
        chat.appendNotice("more");
        assertEquals(1, chat.newLines());
        chat.scrollDown(10);
        chat.visible(40, 10);
        assertEquals(0, chat.newLines());
    }

    @Test
    void scrollingAboveTheTopLoadsBlocksOfOlderLines() {
        var chat = filled(5);
        var calls = new ArrayList<int[]>();
        chat.olderLines((index, count) -> {
            calls.add(new int[] {index, count});
            return IntStream.range(Math.max(0, index - count), index).mapToObj(i -> "old%04d".formatted(i)).toList();
        }, 1200);
        chat.visible(40, 10);
        chat.scrollUp(100);
        chat.visible(40, 10);
        assertEquals(1, calls.size());
        assertEquals(1200, calls.get(0)[0]);
        assertEquals(500, calls.get(0)[1]);
        assertEquals(700, chat.olderIndex());
        chat.scrollUp(1000);
        chat.visible(40, 10);
        assertEquals(200, chat.olderIndex());
        chat.scrollUp(1000);
        chat.visible(40, 10);
        assertEquals(0, chat.olderIndex());
        assertEquals(0, chat.loadOlder());
        assertEquals(5 + 1200, chat.lineCount());
        assertTrue(chat.plainText().startsWith("old0000"));
    }

    @Test
    void selectionIsMarkedAndCopiedAsPlainText() {
        var chat = filled(20);
        chat.visible(40, 10);
        var line = chat.lineAtRow(0);
        chat.selection().select(line, 0, 5);
        var rows = chat.visible(40, 10);
        assertTrue(rows.get(0).styleAt(0).toString().length() > 0);
        assertEquals("line11", chat.selectedText());
        assertFalse(chat.selectedText().contains("\u001b"));
        assertTrue(rows.get(0).toAnsi().contains("\u001b[7m"));
    }

    @Test
    void lineRefFindsTheToolResultAndTheColumn() {
        var chat = new ChatView();
        chat.scrollBar(true, 3);
        chat.apply(new AgentEvent.ToolStarted(A, "t1", "read", "src/Main.java"));
        chat.apply(new AgentEvent.ToolFinished(A, "t1", ToolResultData.ok("a\nb\nc")));
        chat.visible(60, 10);
        var header = chat.refAtRow(0, 10);
        assertEquals("t1", header.toolUseId());
        assertTrue(header.togglesResult());
        assertTrue(header.text().contains("src/Main.java"));
        assertEquals(10, header.column());
        var result = chat.refAtRow(1, 3);
        assertEquals("t1", result.toolUseId());
        assertTrue(result.togglesResult());
        assertNotNull(result.text());
    }

    // ---- ChatMouse ----

    private record Rig(ChatView chat, ChatMouse mouse, List<String> opened, List<String> status,
                       StringWriter written, int[] redraws) {}

    private static Rig rig(ChatView chat, boolean copyOnSelect) {
        var written = new StringWriter();
        var writer = new PrintWriter(written);
        var terminal = (Terminal) Proxy.newProxyInstance(Terminal.class.getClassLoader(),
                new Class<?>[] {Terminal.class}, (proxy, method, args) -> method.getName().equals("writer")
                        ? writer : null);
        var opened = new ArrayList<String>();
        var status = new ArrayList<String>();
        var redraws = new int[1];
        var mouse = new ChatMouse(() -> chat, () -> new Rect(0, 0, 38, 10), () -> new Rect(38, 0, 1, 10),
                () -> new Rect(0, 11, 0, 0), new Clipboard(terminal, true),
                new MouseConfig(true, 3, copyOnSelect), status::add, opened::add, () -> redraws[0]++);
        return new Rig(chat, mouse, opened, status, written, redraws);
    }

    private static void send(org.github.lscoughlin.jeorje.ui.mouse.Region r, Type t, Button b, int x, int y,
                             int clicks, Modifier... mods) {
        var set = EnumSet.noneOf(Modifier.class);
        set.addAll(List.of(mods));
        r.onMouse(new RegionEvent(t, b, set, x, y, clicks));
    }

    @Test
    void wheelScrollsThreeLinesAndShiftScrollsNine() {
        var chat = filled(60);
        var rig = rig(chat, false);
        var text = rig.mouse().regions().get(0);
        chat.visible(40, 10);
        var start = chat.geometry()[0];
        send(text, Type.Wheel, Button.WheelUp, 5, 5, 0);
        chat.visible(40, 10);
        assertEquals(start - 3, chat.geometry()[0]);
        send(text, Type.Wheel, Button.WheelUp, 5, 5, 0, Modifier.Shift);
        chat.visible(40, 10);
        assertEquals(start - 12, chat.geometry()[0]);
        send(text, Type.Wheel, Button.WheelDown, 5, 5, 0);
        chat.visible(40, 10);
        assertEquals(start - 9, chat.geometry()[0]);
        assertEquals(3, rig.redraws()[0]);
    }

    @Test
    void dragSelectsAndCopyEmitsOsc52WithPlainText() {
        var chat = filled(20);
        var rig = rig(chat, false);
        var text = rig.mouse().regions().get(0);
        chat.visible(40, 10);
        send(text, Type.Pressed, Button.Button1, 0, 2, 1);
        send(text, Type.Dragged, Button.Button1, 5, 2, 1);
        send(text, Type.Released, Button.Button1, 5, 2, 1);
        assertEquals("line", chat.selectedText().substring(0, 4));
        var copied = rig.mouse().copySelection();
        assertEquals("line13", copied);
        var out = rig.written().toString();
        var encoded = java.util.Base64.getEncoder().encodeToString("line13".getBytes());
        assertTrue(out.contains("\u001b]52;c;" + encoded + "\u0007"), out);
        assertEquals(List.of(Clipboard.Target.OSC52.status()), rig.status());
    }

    @Test
    void copyOnSelectCopiesAtTheEndOfTheDrag() {
        var chat = filled(20);
        var rig = rig(chat, true);
        var text = rig.mouse().regions().get(0);
        chat.visible(40, 10);
        send(text, Type.Pressed, Button.Button1, 0, 0, 1);
        send(text, Type.Dragged, Button.Button1, 3, 0, 1);
        send(text, Type.Released, Button.Button1, 3, 0, 1);
        assertTrue(rig.written().toString().contains("\u001b]52;c;"));
    }

    @Test
    void doubleClickSelectsAWordAndTripleClickALine() {
        var chat = new ChatView();
        chat.scrollBar(true, 3);
        chat.apply(new AgentEvent.TokenDelta(A, "alpha beta gamma"));
        var rig = rig(chat, false);
        var text = rig.mouse().regions().get(0);
        chat.visible(40, 10);
        send(text, Type.Pressed, Button.Button1, 8, 0, 2);
        send(text, Type.Released, Button.Button1, 8, 0, 2);
        assertEquals("beta", chat.selectedText());
        send(text, Type.Pressed, Button.Button1, 8, 0, 3);
        send(text, Type.Released, Button.Button1, 8, 0, 3);
        assertEquals("alpha beta gamma", chat.selectedText());
    }

    @Test
    void clickOnAToolHeaderTogglesTheResultAndClickOnAPathOpensIt() {
        var chat = new ChatView();
        chat.scrollBar(true, 3);
        chat.apply(new AgentEvent.ToolStarted(A, "t1", "bash", "ls"));
        chat.apply(new AgentEvent.ToolFinished(A, "t1", ToolResultData.ok("one\ntwo\nthree")));
        chat.apply(new AgentEvent.TokenDelta(A, "See src/Main.java for details"));
        var rig = rig(chat, false);
        var text = rig.mouse().regions().get(0);
        chat.visible(60, 10);
        assertFalse(chat.isExpanded("t1"));
        send(text, Type.Pressed, Button.Button1, 3, 0, 1);
        send(text, Type.Released, Button.Button1, 3, 0, 1);
        assertTrue(chat.isExpanded("t1"));
        chat.visible(60, 10);
        // the path line is below the expanded result: header, three lines
        var pathRow = -1;
        var rows = chat.visible(60, 10);
        for (var i = 0; i < rows.size(); i++) if (rows.get(i).toString().contains("src/Main.java")) pathRow = i;
        assertTrue(pathRow > 0);
        send(text, Type.Pressed, Button.Button1, 8, pathRow, 1);
        send(text, Type.Released, Button.Button1, 8, pathRow, 1);
        assertEquals(List.of("src/Main.java"), rig.opened());
    }

    @Test
    void barClickScrollsAndSyncsTheView() {
        var chat = filled(60);
        var rig = rig(chat, false);
        var bar = rig.mouse().regions().get(1);
        chat.visible(40, 10);
        var start = chat.geometry()[0];
        send(bar, Type.Pressed, Button.Button1, 0, 0, 1);
        send(bar, Type.Released, Button.Button1, 0, 0, 1);
        chat.visible(40, 10);
        assertEquals(start - 1, chat.geometry()[0]);
        send(bar, Type.Pressed, Button.Button1, 0, 2, 1);
        send(bar, Type.Released, Button.Button1, 0, 2, 1);
        chat.visible(40, 10);
        assertEquals(start - 1 - 9, chat.geometry()[0]);
    }

    @Test
    void clickOnTheFollowNoticeFollows() {
        var chat = filled(60);
        var rig = rig(chat, false);
        var notice = rig.mouse().regions().get(2);
        chat.visible(40, 10);
        chat.scrollUp(4);
        chat.appendNotice("fresh");
        assertFalse(chat.following());
        send(notice, Type.Pressed, Button.Button1, 2, 0, 1);
        assertTrue(chat.following());
    }
}
