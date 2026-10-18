package org.github.lscoughlin.jeorje.ui.editor;

import static org.github.lscoughlin.jeorje.ui.editor.EditorFixture.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.jline.terminal.MouseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowMouseTest {

    @TempDir
    Path dir;

    private EditorFixture longFile(int count) throws IOException {
        var f = new EditorFixture(dir);
        f.write("long.txt", lines(count));
        f.editor.open(dir.resolve("long.txt"));
        return f;
    }

    // ---- drawing ----

    @Test
    void screenHasFrameTitleScrollBarAndStatusBar() throws IOException {
        var f = longFile(100);
        var screen = f.screen();
        assertEquals(24, screen.size());
        assertTrue(screen.get(0).startsWith("  File"));
        assertTrue(screen.get(1).contains("long.txt"));
        assertTrue(screen.get(1).startsWith("┌"));
        assertTrue(screen.get(22).startsWith("└"));
        assertTrue(screen.get(2).endsWith("▲"));
        assertTrue(screen.get(21).endsWith("▼"));
        assertTrue(screen.get(2).startsWith("│line 1"));
        assertTrue(screen.get(23).contains("Ln 1 Col 1"));
        for (var row : screen) {
            assertEquals(80, row.length());
        }
    }

    @Test
    void lineNumbersAndWhitespaceDisplay() throws IOException {
        var f = new EditorFixture(dir, EditorFixture.keysWith(),
                EditorOptions.defaults().withNoColor(true).withLineNumbers(true).withShowWhitespace(true)
                        .withUseSpaces(false));
        f.type("a b" + TAB + "c" + ENTER + "x");
        var screen = f.screen();
        assertTrue(screen.get(2).contains(" 1 a·b»"), screen.get(2));
        assertTrue(screen.get(3).contains(" 2 x"), screen.get(3));
    }

    @Test
    void tabsAreExpandedToTabStops() {
        var f = new EditorFixture(dir, EditorFixture.keysWith(),
                EditorOptions.defaults().withNoColor(true).withUseSpaces(false).withTabWidth(8));
        f.type("a" + TAB + "b");
        assertTrue(f.screen().get(2).startsWith("│a       b"), f.screen().get(2));
        assertEquals(10, f.editor.column());
    }

    @Test
    void wrapShowsLongLinesOnSeveralRows() {
        var f = new EditorFixture(dir, EditorFixture.keysWith(), EditorOptions.defaults().withNoColor(true).withWrap(true));
        f.type("x".repeat(100));
        var screen = f.screen();
        assertEquals("x".repeat(78), screen.get(2).substring(1, 79));
        assertEquals("x".repeat(22), screen.get(3).substring(1, 23));
        assertTrue(f.status().contains("Col 101"));
    }

    @Test
    void withoutWrapALongLineScrollsSidewaysAndShowsABottomScrollBar() {
        var f = new EditorFixture(dir);
        f.type("0123456789".repeat(12));
        var screen = f.screen();
        assertTrue(screen.get(22).contains("◄"));
        assertTrue(screen.get(22).contains("►"));
        assertTrue(screen.get(2).contains("789"));
        assertFalse(screen.get(2).contains("0123456789012345678901234567890123456789012345678901234567890123456789"
                + "01234567890123456789"));
    }

    @Test
    void viewMenuTogglesWrapOnTheRunningEditor() {
        var f = new EditorFixture(dir);
        f.type("y".repeat(100));
        assertFalse(f.screen().get(3).contains("y"));
        f.type(ALT_V + "w");
        assertTrue(f.screen().get(3).contains("y"));
    }

    // ---- scrolling ----

    @Test
    void cursorKeysScrollTheViewToTheCursor() throws IOException {
        var f = longFile(100);
        f.type(CTRL_END);
        assertTrue(f.screenHas("line 100"));
        assertTrue(f.status().contains("Ln 101"));
        f.type(CTRL_HOME);
        assertTrue(f.screen().get(2).contains("line 1"));
        f.type(PGDN);
        assertEquals(1 + 19, f.editor.line());
        assertTrue(f.editor.scrollTop(0) > 0);
        f.type(PGUP);
        assertEquals(1, f.editor.line());
    }

    @Test
    void wheelScrollsWithoutMovingTheCursor() throws IOException {
        var f = longFile(100);
        f.type(DOWN + DOWN + RIGHT);
        var cursor = f.editor.cursor();
        assertEquals(0, f.editor.scrollTop(0));
        f.wheel(40, 10, false);
        assertEquals(3, f.editor.scrollTop(0));
        assertEquals(cursor, f.editor.cursor());
        assertEquals(3, f.editor.line());
        assertFalse(f.screen().get(3).contains("line 1 "));
        assertTrue(f.screen().get(2).contains("line 4"));
        f.wheel(40, 10, false);
        f.wheel(40, 10, true);
        assertEquals(3, f.editor.scrollTop(0));
        assertEquals(cursor, f.editor.cursor());
    }

    @Test
    void theNextCursorKeyBringsTheViewBack() throws IOException {
        var f = longFile(100);
        for (var i = 0; i < 10; i++) {
            f.wheel(40, 10, false);
        }
        assertEquals(30, f.editor.scrollTop(0));
        assertEquals(1, f.editor.line());
        f.type(RIGHT);
        assertEquals(0, f.editor.scrollTop(0));
        for (var i = 0; i < 10; i++) {
            f.wheel(40, 10, false);
        }
        f.type("z");
        assertEquals(0, f.editor.scrollTop(0));
        assertTrue(f.screenHas("lzine 1"));
    }

    @Test
    void keysThatDoNotMoveTheCursorKeepTheScrolledView() throws IOException {
        var f = longFile(100);
        f.wheel(40, 10, false);
        f.type(INS);
        f.type(ALT_E + ESC);
        assertEquals(3, f.editor.scrollTop(0));
    }

    @Test
    void wheelStopsAtTheEnds() throws IOException {
        var f = longFile(30);
        f.wheel(40, 10, true);
        assertEquals(0, f.editor.scrollTop(0));
        for (var i = 0; i < 50; i++) {
            f.wheel(40, 10, false);
        }
        assertTrue(f.editor.scrollTop(0) <= 31 - 19);
        assertTrue(f.screenHas("line 30"));
    }

    @Test
    void wheelWithShiftScrollsSideways() {
        var f = new EditorFixture(dir);
        f.type("0123456789".repeat(12));
        f.type(HOME);
        assertTrue(f.screen().get(2).startsWith("│0123"));
        f.wheel(40, 10, false, MouseEvent.Modifier.Shift);
        assertTrue(f.screen().get(2).startsWith("│3456"), f.screen().get(2));
        assertEquals(1, f.editor.column());
    }

    @Test
    void scrollBarArrowsAndTrackWork() throws IOException {
        var f = longFile(100);
        f.click(79, 21); // down arrow
        assertEquals(1, f.editor.scrollTop(0));
        f.click(79, 2); // up arrow
        assertEquals(0, f.editor.scrollTop(0));
        f.click(79, 19); // track below the thumb: one page
        assertEquals(19, f.editor.scrollTop(0));
        f.click(79, 4); // track above the thumb
        assertEquals(0, f.editor.scrollTop(0));
        assertEquals(1, f.editor.line());
    }

    @Test
    void scrollBarThumbCanBeDragged() throws IOException {
        var f = longFile(100);
        f.drag(79, 3, 79, 12);
        assertTrue(f.editor.scrollTop(0) > 20, "top " + f.editor.scrollTop(0));
        assertEquals(1, f.editor.line());
    }

    @Test
    void bottomScrollBarScrollsSideways() {
        var f = new EditorFixture(dir);
        f.type("0123456789".repeat(12) + HOME);
        f.click(78, 22); // right arrow
        assertTrue(f.screen().get(2).startsWith("│1234"), f.screen().get(2));
        f.click(1, 22); // left arrow
        assertTrue(f.screen().get(2).startsWith("│0123"));
    }

    // ---- click, drag, multi click ----

    @Test
    void clickSetsTheCursor() throws IOException {
        var f = longFile(10);
        f.click(6, 4); // screen row 2 is line 1, so row 4 is line 3
        assertEquals(3, f.editor.line());
        assertEquals(6, f.editor.column());
        f.click(60, 2); // after the end of the line
        assertEquals(1, f.editor.line());
        assertEquals(7, f.editor.column());
    }

    @Test
    void clickBelowTheTextPutsTheCursorOnTheLastLine() {
        var f = new EditorFixture(dir);
        f.type("one" + ENTER + "two");
        f.click(2, 15);
        assertEquals(2, f.editor.line());
    }

    @Test
    void dragSelectsText() {
        var f = new EditorFixture(dir);
        f.type("hello world");
        f.drag(2, 2, 7, 2);
        assertEquals("ello ", f.editor.selectedText());
        f.drag(10, 2, 3, 2);
        assertEquals("llo wor", f.editor.selectedText());
    }

    @Test
    void dragAcrossLinesSelectsLines() {
        var f = new EditorFixture(dir);
        f.type("one" + ENTER + "two" + ENTER + "three");
        f.drag(2, 2, 3, 4);
        assertEquals("ne\ntwo\nth", f.editor.selectedText());
    }

    @Test
    void doubleClickSelectsAWordAndTripleClickSelectsALine() {
        var f = new EditorFixture(dir);
        f.type("foo bar baz" + ENTER + "second");
        f.click(6, 2);
        f.clickAgain(6, 2);
        assertEquals("bar", f.editor.selectedText());
        f.clickAgain(6, 2);
        assertEquals("foo bar baz\n", f.editor.selectedText());
        f.click(6, 2); // a later click is a single click again
        assertEquals("", f.editor.selectedText());
    }

    @Test
    void doubleClickOnPunctuationAndSpace() {
        var f = new EditorFixture(dir);
        f.type("a  ==  b");
        f.click(3, 2);
        f.clickAgain(3, 2);
        assertEquals("  ", f.editor.selectedText());
        f.click(5, 2);
        f.clickAgain(5, 2);
        assertEquals("==", f.editor.selectedText());
    }

    @Test
    void shiftClickExtendsTheSelection() {
        var f = new EditorFixture(dir);
        f.type("abcdefghij" + HOME + RIGHT + RIGHT);
        f.clock.addAndGet(1000);
        f.mouse(MouseEvent.Type.Pressed, MouseEvent.Button.Button1, 8, 2, MouseEvent.Modifier.Shift);
        f.mouse(MouseEvent.Type.Released, MouseEvent.Button.Button1, 8, 2, MouseEvent.Modifier.Shift);
        assertEquals("cdefg", f.editor.selectedText());
    }

    @Test
    void dragBelowTheWindowScrollsAndSelects() throws IOException {
        var f = longFile(100);
        f.clock.addAndGet(1000);
        f.mouse(MouseEvent.Type.Pressed, MouseEvent.Button.Button1, 3, 5);
        f.mouse(MouseEvent.Type.Dragged, MouseEvent.Button.Button1, 3, 23); // below the window
        var top = f.editor.scrollTop(0);
        assertTrue(top > 0, "top " + top);
        f.mouse(MouseEvent.Type.Dragged, MouseEvent.Button.Button1, 3, 23);
        assertTrue(f.editor.scrollTop(0) > top);
        f.mouse(MouseEvent.Type.Released, MouseEvent.Button.Button1, 3, 23);
        assertTrue(f.editor.selectedText().startsWith("ne 4"));
        assertTrue(f.editor.selectedText().lines().count() > 20);
    }

    @Test
    void dragAboveTheWindowScrollsUp() throws IOException {
        var f = longFile(100);
        for (var i = 0; i < 20; i++) {
            f.wheel(40, 10, false);
        }
        var top = f.editor.scrollTop(0);
        assertEquals(60, top);
        f.clock.addAndGet(1000);
        f.mouse(MouseEvent.Type.Pressed, MouseEvent.Button.Button1, 3, 10);
        f.mouse(MouseEvent.Type.Dragged, MouseEvent.Button.Button1, 3, 0);
        assertTrue(f.editor.scrollTop(0) < top);
        f.editor.tick();
        f.editor.tick();
        assertTrue(f.editor.scrollTop(0) < top - 1);
        f.mouse(MouseEvent.Type.Released, MouseEvent.Button.Button1, 3, 0);
        var settled = f.editor.scrollTop(0);
        f.editor.tick();
        assertEquals(settled, f.editor.scrollTop(0));
    }

    @Test
    void theDragStaysWithTheWindowWhereItStarted() {
        var f = new EditorFixture(dir);
        f.type("abc");
        f.clock.addAndGet(1000);
        f.mouse(MouseEvent.Type.Pressed, MouseEvent.Button.Button1, 2, 2);
        f.mouse(MouseEvent.Type.Dragged, MouseEvent.Button.Button1, 2, 0); // over the menu bar
        f.mouse(MouseEvent.Type.Released, MouseEvent.Button.Button1, 2, 0);
        assertFalse(f.editor.menuOpen());
    }

    @Test
    void typedTextWorksAfterMouseSelection() {
        var f = new EditorFixture(dir);
        f.type("hello world");
        f.drag(2, 2, 7, 2);
        f.type("J");
        assertEquals("hJworld", f.editor.text().replace("ello ", ""));
    }

    // ---- split windows ----

    @Test
    void splitWindowShowsTwoFramesAndF6SwitchesBetweenThem() throws IOException {
        var f = longFile(100);
        f.type(ALT_V + "s");
        assertEquals(2, f.editor.windowCount());
        var frames = f.screen().stream().filter(r -> r.startsWith("┌")).count();
        assertEquals(2, frames);
        assertEquals(0, f.editor.activeWindow());
        f.type(F6);
        assertEquals(1, f.editor.activeWindow());
        f.type(CTRL_END);
        assertEquals(101, f.editor.line());
        f.type(F6);
        assertEquals(1, f.editor.line());
    }

    @Test
    void thereWindowsShowTheSameFileWithIndependentViews() throws IOException {
        var f = longFile(100);
        f.type(ALT_V + "s" + F6 + CTRL_END);
        assertTrue(f.screenHas("line 100"));
        assertTrue(f.screenHas("line 1 "));
        f.type("X");
        f.type(F6);
        assertEquals(0, f.editor.cursor());
        assertTrue(f.editor.text().endsWith("X"));
    }

    @Test
    void editsInOneWindowMoveTheCursorOfTheOther() {
        var f = new EditorFixture(dir);
        f.type("world");
        f.type(ALT_V + "s");
        f.type(F6);
        f.type(CTRL_HOME + "hello ");
        f.type(F6);
        assertEquals(11, f.editor.cursor());
        assertEquals("hello world", f.editor.text());
    }

    @Test
    void wheelScrollsOnlyTheWindowUnderThePointer() throws IOException {
        var f = longFile(100);
        f.type(ALT_V + "s");
        f.wheel(40, 18, false); // lower window
        assertEquals(0, f.editor.scrollTop(0));
        assertEquals(3, f.editor.scrollTop(1));
        f.wheel(40, 5, false); // upper window
        assertEquals(3, f.editor.scrollTop(0));
        assertEquals(3, f.editor.scrollTop(1));
        assertEquals(0, f.editor.cursor());
    }

    @Test
    void clickInTheOtherWindowMakesItActive() throws IOException {
        var f = longFile(100);
        f.type(ALT_V + "s");
        f.click(3, 15);
        assertEquals(1, f.editor.activeWindow());
        f.click(3, 4);
        assertEquals(0, f.editor.activeWindow());
    }

    @Test
    void splitTwiceClosesTheSecondWindow() {
        var f = new EditorFixture(dir);
        f.type(ALT_V + "s");
        assertEquals(2, f.editor.windowCount());
        f.type(ALT_V + "s");
        assertEquals(1, f.editor.windowCount());
    }

    @Test
    void switchWindowWithOneWindowShowsANotice() {
        var f = new EditorFixture(dir);
        f.type(F6);
        assertTrue(f.status().contains("only one window"));
    }

    @Test
    void secondWindowCanShowAnotherFile() throws IOException {
        var f = new EditorFixture(dir);
        f.write("one.txt", "first file\n");
        f.write("two.txt", "second file\n");
        f.editor.open(dir.resolve("one.txt"));
        f.editor.openInOtherWindow(dir.resolve("two.txt"));
        assertEquals(2, f.editor.windowCount());
        assertEquals(0, f.editor.activeWindow());
        assertTrue(f.screenHas("first file"));
        assertTrue(f.screenHas("second file"));
        f.type(F6);
        assertEquals("second file\n", f.editor.text());
        f.type("X" + CTRL_S);
        assertEquals("Xsecond file\n", f.read("two.txt"));
        assertEquals("first file\n", f.read("one.txt"));
    }

    @Test
    void closeWithTwoModifiedFilesAsksForBoth() throws IOException {
        var f = new EditorFixture(dir);
        f.write("one.txt", "a");
        f.write("two.txt", "b");
        f.editor.open(dir.resolve("one.txt"));
        f.editor.openInOtherWindow(dir.resolve("two.txt"));
        f.type("1");
        f.type(F6 + "2");
        f.type(ALT_X);
        assertTrue(f.screenHas("one.txt"));
        f.type(ENTER); // Save
        assertTrue(f.editor.dialogOpen());
        assertTrue(f.screenHas("two.txt"));
        f.type(ENTER);
        assertTrue(f.editor.isClosed());
        assertEquals("1a", f.read("one.txt"));
        assertEquals("2b", f.read("two.txt"));
    }

    // ---- small and odd screens ----

    @Test
    void tinyScreenShowsANotice() {
        var f = new EditorFixture(dir);
        f.editor.setSize(8, 3);
        assertTrue(f.screen().get(0).startsWith("Terminal"));
        f.editor.setSize(80, 24);
        assertEquals(24, f.screen().size());
    }

    @Test
    void screenSizeChangeRedrawsAtTheNewSize() {
        var f = new EditorFixture(dir);
        f.editor.setSize(40, 10);
        var screen = f.screen();
        assertEquals(10, screen.size());
        assertEquals(40, screen.get(0).length());
        assertNotEquals("", screen.get(9).trim());
    }

    @Test
    void copyOnSelectCopiesAfterADrag() {
        var f = new EditorFixture(dir);
        f.type("hello world");
        f.drag(2, 2, 7, 2);
        assertEquals("", f.clipboard.paste());
        var g = new EditorFixture(dir);
        var editor = QEditor.builder().baseDir(dir).clipboard(g.clipboard).copyOnSelect(true)
                .options(EditorOptions.defaults().withNoColor(true)).build();
        editor.setSize(80, 24);
        editor.feed("hello world");
        editor.mouse(new MouseEvent(MouseEvent.Type.Pressed, MouseEvent.Button.Button1,
                java.util.EnumSet.noneOf(MouseEvent.Modifier.class), 2, 2));
        editor.mouse(new MouseEvent(MouseEvent.Type.Dragged, MouseEvent.Button.Button1,
                java.util.EnumSet.noneOf(MouseEvent.Modifier.class), 7, 2));
        editor.mouse(new MouseEvent(MouseEvent.Type.Released, MouseEvent.Button.Button1,
                java.util.EnumSet.noneOf(MouseEvent.Modifier.class), 7, 2));
        assertEquals("ello ", g.clipboard.paste());
    }
}
