package org.github.lscoughlin.jeorje.ui.mouse;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Turn;
import org.github.lscoughlin.jeorje.core.session.TranscriptWriter;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.MouseEvent.Button;
import org.jline.terminal.MouseEvent.Modifier;
import org.jline.terminal.MouseEvent.Type;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MouseTest {
    private static MouseEvent ev(Type t, Button b, int x, int y, Modifier... m) {
        var mods = EnumSet.noneOf(Modifier.class);
        mods.addAll(List.of(m));
        return new MouseEvent(t, b, mods, x, y);
    }

    private static final class Recorder implements Region {
        final Rect rect;
        final List<RegionEvent> got = new ArrayList<>();

        Recorder(Rect rect) { this.rect = rect; }

        @Override public Rect bounds() { return rect; }

        @Override public void onMouse(RegionEvent e) { got.add(e); }
    }

    // ---- MouseInput ----

    @Test
    void trackingStartsAndStopsOnClose() {
        var t = new StubTerminal(true);
        var input = new MouseInput(t.terminal, MouseConfig.DEFAULT, new RegionRegistry(), s -> { });
        assertTrue(input.start());
        assertEquals(Terminal.MouseTracking.Button, t.lastTracking());
        input.close();
        assertEquals(Terminal.MouseTracking.Off, t.lastTracking());
        assertFalse(input.isTracking());
    }

    @Test
    void trackingGoesOffOnSignalAndChainsPreviousHandler() {
        var t = new StubTerminal(true);
        var seen = new ArrayList<String>();
        t.handlers.put(Terminal.Signal.INT, s -> seen.add("old"));
        var input = new MouseInput(t.terminal, MouseConfig.DEFAULT, new RegionRegistry(), s -> { });
        input.start();
        t.handlers.get(Terminal.Signal.INT).handle(Terminal.Signal.INT);
        assertEquals(Terminal.MouseTracking.Off, t.lastTracking());
        assertEquals(List.of("old"), seen);
        input.close();
    }

    @Test
    void suspendAndResume() {
        var t = new StubTerminal(true);
        var input = new MouseInput(t.terminal, MouseConfig.DEFAULT, new RegionRegistry(), s -> { });
        input.start();
        input.suspend();
        assertEquals(Terminal.MouseTracking.Off, t.lastTracking());
        input.resume();
        assertEquals(Terminal.MouseTracking.Button, t.lastTracking());
        input.close();
    }

    @Test
    void noMouseSupportShowsOneNotice() {
        var t = new StubTerminal(false);
        var notices = new ArrayList<String>();
        var input = new MouseInput(t.terminal, MouseConfig.DEFAULT, new RegionRegistry(), notices::add);
        assertFalse(input.start());
        assertFalse(input.start());
        assertEquals(List.of(MouseInput.NO_MOUSE_NOTICE), notices);
        assertTrue(t.trackingCalls.isEmpty());
    }

    @Test
    void disabledConfigDoesNotTrack() {
        var t = new StubTerminal(true);
        var input = new MouseInput(
                t.terminal, new MouseConfig(false, 3, false), new RegionRegistry(), s -> fail("no notice"));
        assertFalse(input.start());
        assertTrue(t.trackingCalls.isEmpty());
    }

    @Test
    void configFromMap() {
        var c = MouseConfig.fromMap(java.util.Map.of(
                "ui.mouse.enabled", false, "ui.mouse.wheelLines", 5, "ui.mouse.copyOnSelect", true));
        assertEquals(new MouseConfig(false, 5, true), c);
    }

    @Test
    void pumpReadsAndCountsClicks() {
        var t = new StubTerminal(true);
        var registry = new RegionRegistry();
        var region = new Recorder(new Rect(0, 0, 10, 10));
        registry.register(region);
        var now = new long[] {1000};
        var input = new MouseInput(t.terminal, MouseConfig.DEFAULT, registry, s -> { }, () -> now[0]);
        for (var i = 0; i < 4; i++) {
            t.events.add(ev(Type.Pressed, Button.Button1, 2, 2));
            input.pump();
            now[0] += 100;
        }
        t.events.add(ev(Type.Pressed, Button.Button1, 3, 2));
        input.pump();
        var counts = region.got.stream().map(RegionEvent::clicks).toList();
        assertEquals(List.of(1, 2, 3, 1, 1), counts);
    }

    @Test
    void clickCounterWindow() {
        var now = new long[] {0};
        var c = new ClickCounter(() -> now[0]);
        assertEquals(1, c.press(1, 1));
        now[0] = 400;
        assertEquals(2, c.press(1, 1));
        now[0] = 801;
        assertEquals(1, c.press(1, 1));
    }

    // ---- RegionRegistry ----

    @Test
    void wheelGoesToRegionUnderPointer() {
        var registry = new RegionRegistry();
        var a = new ScrollModel(3);
        a.setContent(100, 10);
        a.scrollTo(50);
        var b = new ScrollModel(3);
        b.setContent(100, 10);
        b.scrollTo(50);
        registry.register(new ScrollRegion(new Rect(0, 0, 40, 10), a));
        registry.register(new ScrollRegion(new Rect(40, 0, 40, 10), b));
        registry.dispatch(ev(Type.Wheel, Button.WheelUp, 45, 3), 0);
        assertEquals(50, a.offset());
        assertEquals(47, b.offset());
        registry.dispatch(ev(Type.Wheel, Button.WheelDown, 5, 3), 0);
        assertEquals(53, a.offset());
        assertEquals(47, b.offset());
    }

    @Test
    void dragStaysWithStartRegion() {
        var registry = new RegionRegistry();
        var a = new Recorder(new Rect(0, 0, 10, 10));
        var b = new Recorder(new Rect(10, 0, 10, 10));
        registry.register(a);
        registry.register(b);
        registry.dispatch(ev(Type.Pressed, Button.Button1, 5, 5), 1);
        registry.dispatch(ev(Type.Dragged, Button.Button1, 15, 5), 1);
        registry.dispatch(ev(Type.Dragged, Button.Button1, 25, 50), 1);
        registry.dispatch(ev(Type.Released, Button.Button1, 15, 5), 1);
        assertEquals(4, a.got.size());
        assertEquals(15 - 0, a.got.get(1).x());
        assertTrue(b.got.isEmpty());
        registry.dispatch(ev(Type.Moved, Button.NoButton, 15, 5), 0);
        assertEquals(1, b.got.size());
        assertEquals(5, b.got.get(0).x());
    }

    @Test
    void shiftWheelScrollsSideways() {
        var registry = new RegionRegistry();
        var m = new ScrollModel(3);
        m.setContent(100, 10);
        m.setHorizontal(300, 80);
        registry.register(new ScrollRegion(new Rect(0, 0, 80, 10), m));
        registry.dispatch(ev(Type.Wheel, Button.WheelDown, 1, 1, Modifier.Shift), 0);
        assertEquals(3, m.hOffset());
        assertEquals(0, m.offset());
        registry.dispatch(ev(Type.Wheel, Button.WheelUp, 1, 1, Modifier.Shift), 0);
        assertEquals(0, m.hOffset());
    }

    @Test
    void topmostRegionWins() {
        var registry = new RegionRegistry();
        var under = new Recorder(new Rect(0, 0, 10, 10));
        var over = new Recorder(new Rect(2, 2, 3, 3));
        registry.register(under);
        registry.register(over);
        registry.dispatch(ev(Type.Moved, Button.NoButton, 3, 3), 0);
        assertEquals(1, over.got.size());
        assertTrue(under.got.isEmpty());
        registry.unregister(over);
        registry.dispatch(ev(Type.Moved, Button.NoButton, 3, 3), 0);
        assertEquals(1, under.got.size());
    }

    // ---- ScrollBar ----

    private static RegionEvent re(Type t, int y) {
        return new RegionEvent(t, Button.Button1, EnumSet.noneOf(Modifier.class), 0, y, 1);
    }

    @Test
    void scrollBarActions() {
        var m = new ScrollModel(3);
        m.setContent(100, 10);
        m.scrollTo(40);
        var bar = new ScrollBar(new Rect(79, 0, 1, 12), m);
        bar.onMouse(re(Type.Pressed, 0));
        assertEquals(39, m.offset());
        bar.onMouse(re(Type.Pressed, 11));
        assertEquals(40, m.offset());
        // thumb size 10*10/100 = 1; thumb at row 1 + round(9*40/90)=4 -> row 5
        assertEquals(1, bar.thumbSize());
        assertEquals(4, bar.thumbPosition());
        bar.onMouse(re(Type.Pressed, 2));
        assertEquals(31, m.offset());
        bar.onMouse(re(Type.Pressed, 10));
        assertEquals(40, m.offset());
        assertEquals(12, bar.render().size());
        assertEquals(ScrollBar.UP, bar.render().get(0));
        assertEquals(ScrollBar.THUMB, bar.render().get(1 + bar.thumbPosition()));
    }

    @Test
    void scrollBarThumbDrag() {
        var m = new ScrollModel(3);
        m.setContent(100, 10);
        var bar = new ScrollBar(new Rect(79, 0, 1, 12), m);
        bar.onMouse(re(Type.Pressed, 1));
        bar.onMouse(re(Type.Dragged, 10));
        assertEquals(90, m.offset());
        bar.onMouse(re(Type.Dragged, 50));
        assertEquals(90, m.offset());
        bar.onMouse(re(Type.Released, 50));
        bar.onMouse(re(Type.Dragged, 1));
        assertEquals(90, m.offset());
    }

    // ---- Follow mode ----

    private static List<String> numbered(int from, int count) {
        var out = new ArrayList<String>();
        for (var i = 0; i < count; i++) out.add("line " + (from + i));
        return out;
    }

    @Test
    void followModeAndNotice() {
        var m = new ScrollModel(3);
        var chat = new ChatScroll(m, null, 0);
        chat.setViewport(10);
        chat.append(numbered(0, 30));
        assertTrue(chat.following());
        assertEquals(20, m.offset());
        chat.append("more");
        assertEquals(21, m.offset());

        m.wheel(Button.WheelUp, false);
        assertFalse(chat.following());
        assertEquals(18, m.offset());
        assertTrue(chat.notice().isEmpty());
        chat.append(numbered(100, 4));
        assertEquals(18, m.offset());
        assertEquals(4, chat.newLines());
        assertEquals("4 new lines - Ctrl-X b or click here to follow", chat.notice().orElseThrow());

        m.wheel(Button.WheelDown, false);
        m.wheel(Button.WheelDown, false);
        assertFalse(chat.following());
        m.wheel(Button.WheelDown, false);
        assertTrue(chat.following());
        assertTrue(chat.notice().isEmpty());
        assertEquals(0, chat.newLines());
    }

    @Test
    void followActionResumes() {
        var m = new ScrollModel(3);
        var chat = new ChatScroll(m, null, 0);
        chat.setViewport(5);
        chat.append(numbered(0, 20));
        m.scrollBy(-6);
        chat.append(numbered(20, 2));
        assertFalse(chat.following());
        chat.follow();
        assertTrue(chat.following());
        assertTrue(m.atBottom());
        assertTrue(chat.notice().isEmpty());
    }

    @Test
    void loadsOlderLinesInBlocksOf500() {
        var all = numbered(0, 1300);
        var calls = new ArrayList<String>();
        OlderLines older = (index, count) -> {
            calls.add(index + ":" + count);
            return all.subList(Math.max(0, index - count), index);
        };
        var m = new ScrollModel(3);
        var chat = new ChatScroll(m, older, 1000);
        chat.setViewport(10);
        chat.append(all.subList(1000, 1300));
        chat.follow();
        m.scrollTo(0);
        assertEquals(1000, chat.firstIndex());
        assertFalse(chat.following());
        var before = chat.lines().size();
        m.wheel(Button.WheelUp, false);
        assertEquals(before + 500, chat.lines().size());
        assertEquals(500, chat.firstIndex());
        assertEquals("line 500", chat.lines().get(0));
        assertEquals(497, m.offset());
        assertEquals(List.of("1000:500"), calls);
        m.scrollTo(0);
        m.scrollBy(-1);
        assertEquals(0, chat.firstIndex());
        assertEquals(1300, chat.lines().size());
        m.scrollTo(0);
        m.scrollBy(-1);
        assertEquals(0, chat.firstIndex());
        assertEquals(1300, chat.lines().size());
    }

    @Test
    void keepsLastTenThousandLines() {
        var m = new ScrollModel(3);
        var chat = new ChatScroll(m, null, 0);
        chat.setViewport(10);
        chat.append(numbered(0, 10_005));
        assertEquals(ChatScroll.MAX_LINES, chat.lines().size());
        assertEquals(5, chat.firstIndex());
        assertEquals("line 5", chat.lines().get(0));
    }

    @Test
    void transcriptOlderLines(@TempDir java.nio.file.Path dir) {
        var file = dir.resolve("transcript.jsonl");
        try (var w = new TranscriptWriter(file)) {
            var agent = new AgentId("main");
            w.writeTurn(new Turn(agent, 1, List.of(Message.user("hello\nworld"), Message.assistant("hi"))),
                    java.time.Instant.now());
            w.writeTurn(new Turn(agent, 2, List.of(Message.user("again"))), java.time.Instant.now());
        }
        var src = new TranscriptOlderLines(file, null);
        assertEquals(List.of("> hello", "> world", "hi", "> again"), src.allLines());
        assertEquals(List.of("hi"), src.before(3, 1));
        assertEquals(List.of("> hello", "> world"), src.before(2, 500));
    }

    // ---- Selection and copy ----

    private record Fixture(ChatRegion region, Clipboard clipboard, StubTerminal term, List<String> status,
            ChatScroll scroll, List<String> opened, List<String> toggled) {}

    private static Fixture fixture(boolean osc52, boolean copyOnSelect, List<AttributedString> lines) {
        var t = new StubTerminal(true);
        var m = new ScrollModel(3);
        var scroll = new ChatScroll(m, null, 0);
        scroll.setViewport(5);
        scroll.append(lines.stream().map(AttributedString::toString).toList());
        var clip = new Clipboard(t.terminal, osc52);
        var status = new ArrayList<String>();
        var opened = new ArrayList<String>();
        var toggled = new ArrayList<String>();
        var region = new ChatRegion(new Rect(0, 0, 40, 5), scroll, () -> lines, clip,
                new MouseConfig(true, 3, copyOnSelect), status::add);
        region.addClickHandler(LineClick.toolResult(
                l -> lines.get(l).toString().startsWith("[bash]") ? java.util.Optional.of("tool-" + l)
                        : java.util.Optional.empty(), toggled::add));
        region.addClickHandler(LineClick.filePath(opened::add));
        return new Fixture(region, clip, t, status, scroll, opened, toggled);
    }

    private static List<AttributedString> colored(String... texts) {
        var red = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold();
        return java.util.Arrays.stream(texts).map(s -> new AttributedString(s, red)).toList();
    }

    private static void send(ChatRegion r, Type t, int x, int y, int clicks) {
        r.onMouse(new RegionEvent(t, Button.Button1, EnumSet.noneOf(Modifier.class), x, y, clicks));
    }

    @Test
    void dragSelectsAndCopiesPlainText() {
        var f = fixture(true, false, colored("hello world", "second line", "third"));
        send(f.region, Type.Pressed, 6, 0, 1);
        send(f.region, Type.Dragged, 5, 1, 1);
        send(f.region, Type.Released, 5, 1, 1);
        var text = f.region.copySelection();
        assertEquals("world\nsecond", text);
        assertFalse(text.contains("\u001b"));
        var out = f.term.written.toString();
        var expected = java.util.Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("\u001b]52;c;" + expected + "\u0007", out);
        assertEquals(List.of(Clipboard.Target.OSC52.status()), f.status);
    }

    @Test
    void internalClipboardFallback() {
        var f = fixture(false, true, colored("alpha beta"));
        send(f.region, Type.Pressed, 0, 0, 1);
        send(f.region, Type.Dragged, 4, 0, 1);
        send(f.region, Type.Released, 4, 0, 1);
        assertEquals("alpha", f.clipboard.internalText());
        assertEquals("", f.term.written.toString());
        assertEquals(List.of(Clipboard.Target.INTERNAL.status()), f.status);
    }

    @Test
    void copyOnSelectOffDoesNotCopy() {
        var f = fixture(true, false, colored("alpha beta"));
        send(f.region, Type.Pressed, 0, 0, 1);
        send(f.region, Type.Dragged, 4, 0, 1);
        send(f.region, Type.Released, 4, 0, 1);
        assertEquals("", f.clipboard.internalText());
        assertTrue(f.status.isEmpty());
    }

    @Test
    void doubleAndTripleClick() {
        var f = fixture(false, false, colored("foo bar_baz qux"));
        send(f.region, Type.Pressed, 6, 0, 2);
        assertEquals("bar_baz", f.region.selection().text(colored("foo bar_baz qux")));
        send(f.region, Type.Released, 6, 0, 2);
        send(f.region, Type.Pressed, 6, 0, 3);
        assertEquals("foo bar_baz qux", f.region.selection().text(colored("foo bar_baz qux")));
        send(f.region, Type.Released, 6, 0, 3);
        assertTrue(f.region.selection().isActive());
    }

    @Test
    void clickWithoutDragClearsSelection() {
        var f = fixture(false, false, colored("foo bar"));
        send(f.region, Type.Pressed, 0, 0, 2);
        send(f.region, Type.Released, 0, 0, 2);
        assertTrue(f.region.selection().isActive());
        send(f.region, Type.Pressed, 5, 0, 1);
        send(f.region, Type.Released, 5, 0, 1);
        assertFalse(f.region.selection().isActive());
    }

    @Test
    void dragOutsideRegionClampsInside() {
        var f = fixture(false, false, colored("one", "two", "three"));
        send(f.region, Type.Pressed, 1, 0, 1);
        send(f.region, Type.Dragged, 300, 99, 1);
        assertEquals("ne\ntwo\nthree", f.region.selection().text(colored("one", "two", "three")));
    }

    @Test
    void clickExpandsToolResultAndOpensFilePath() {
        var f = fixture(false, false, colored("[bash] ls", "see src/main/Foo.java:12 for more"));
        send(f.region, Type.Pressed, 2, 0, 1);
        send(f.region, Type.Released, 2, 0, 1);
        assertEquals(List.of("tool-0"), f.toggled);
        assertTrue(f.opened.isEmpty());
        send(f.region, Type.Pressed, 8, 1, 1);
        send(f.region, Type.Released, 8, 1, 1);
        assertEquals(List.of("src/main/Foo.java"), f.opened);
        send(f.region, Type.Pressed, 1, 1, 1);
        send(f.region, Type.Released, 1, 1, 1);
        assertEquals(1, f.opened.size());
    }

    @Test
    void chatRegionWheelScrolls() {
        var f = fixture(false, false, colored("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"));
        assertEquals(5, f.scroll.model().offset());
        f.region.onMouse(new RegionEvent(Type.Wheel, Button.WheelUp, EnumSet.noneOf(Modifier.class), 0, 0, 0));
        assertEquals(2, f.scroll.model().offset());
        assertFalse(f.scroll.following());
    }

    @Test
    void osc52Guess() {
        assertFalse(Clipboard.osc52Likely(java.util.Map.of("TERM", "dumb")));
        assertTrue(Clipboard.osc52Likely(java.util.Map.of("TERM", "xterm-256color")));
    }
}
