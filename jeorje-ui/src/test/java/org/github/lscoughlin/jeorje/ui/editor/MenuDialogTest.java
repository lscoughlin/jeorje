package org.github.lscoughlin.jeorje.ui.editor;

import static org.github.lscoughlin.jeorje.ui.editor.EditorFixture.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.jline.terminal.MouseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MenuDialogTest {

    @TempDir
    Path dir;

    @Test
    void menuBarShowsAllMenus() {
        var f = new EditorFixture(dir);
        var bar = f.screen().get(0);
        for (var name : new String[] {"File", "Edit", "Search", "View", "Options", "Help"}) {
            assertTrue(bar.contains(name), name);
        }
    }

    @Test
    void altKeysOpenTheMenus() {
        var f = new EditorFixture(dir);
        f.type(ALT_F);
        assertTrue(f.editor.menuOpen());
        assertTrue(f.screenHas("Save As..."));
        assertTrue(f.screenHas("Close and return to chat"));
        f.type(ESC);
        assertFalse(f.editor.menuOpen());
        f.type(ALT_E);
        assertTrue(f.screenHas("Select All"));
        f.type(ESC);
        f.type("\u001bs");
        assertTrue(f.screenHas("Go To Line"));
        f.type(ESC);
        f.type(ALT_V);
        assertTrue(f.screenHas("Show Changes"));
        f.type(ESC + "\u001bo");
        assertTrue(f.screenHas("Tab Width"));
        f.type(ESC + "\u001bh");
        assertTrue(f.screenHas("About the Editor"));
    }

    @Test
    void f10OpensTheFirstMenuAndArrowsMove() {
        var f = new EditorFixture(dir);
        f.type(F10);
        assertTrue(f.editor.menuOpen());
        assertTrue(f.screenHas("Revert"));
        f.type(RIGHT);
        assertTrue(f.screenHas("Select All"));
        f.type(LEFT + LEFT);
        assertTrue(f.screenHas("About the Editor"));
        f.type(RIGHT);
        assertTrue(f.screenHas("Revert"));
        f.type(ESC);
        assertFalse(f.editor.menuOpen());
    }

    @Test
    void menuItemRunsWithEnterAndWithItsHotLetter() {
        var f = new EditorFixture(dir);
        f.type("some text");
        f.type(F10 + RIGHT); // Edit
        f.type("a"); // Select All
        assertFalse(f.editor.menuOpen());
        assertEquals("some text", f.editor.selectedText());
        f.type(F10 + RIGHT + ENTER); // Undo is the first item
        assertEquals("", f.editor.text());
        f.type(F10 + RIGHT + DOWN + ENTER); // Redo
        assertEquals("some text", f.editor.text());
    }

    @Test
    void statusBarShowsHelpOfTheSelectedMenuItem() {
        var f = new EditorFixture(dir);
        f.type(F10);
        assertTrue(f.status().contains("Save the file"));
        f.type(DOWN);
        assertTrue(f.status().contains("another name"));
    }

    @Test
    void menusShowTheCurrentKeys() {
        var f = new EditorFixture(dir);
        f.type(F10);
        assertTrue(f.screenHas("Ctrl-S"));
        assertTrue(f.screenHas("Alt-X"));
        f.type(ESC + ALT_E);
        assertTrue(f.screenHas("Ctrl-Z"));
        assertTrue(f.screenHas("Ctrl-Y"));
        assertTrue(f.screenHas("Shift-Del"));
        assertTrue(f.screenHas("Ctrl-Ins"));
        assertTrue(f.screenHas("Shift-Ins"));
        assertTrue(f.screenHas("Ctrl-A"));
        f.type(ESC + "\u001bs");
        assertTrue(f.screenHas("Ctrl-Q f"));
        assertTrue(f.screenHas("F3"));
        assertTrue(f.screenHas("Ctrl-Q a"));
        assertTrue(f.screenHas("Ctrl-G"));
    }

    @Test
    void configuredKeysAppearInMenusAndWork() {
        var keys = EditorFixture.keysWith("save", "Ctrl-W", "find", "Ctrl-Q x", "undo", "F5");
        var f = new EditorFixture(dir, keys, EditorOptions.defaults().withNoColor(true));
        f.type(F10);
        assertTrue(f.screenHas("Ctrl-W"));
        assertFalse(f.screenHas("Ctrl-S"));
        f.type(ESC + ALT_E);
        assertTrue(f.screenHas("F5"));
        f.type(ESC);
        f.type("\u001bs");
        assertTrue(f.screenHas("Ctrl-Q x"));
        f.type(ESC);
        f.type("abc");
        f.type("\u001b[15~"); // F5 undoes
        assertEquals("", f.editor.text());
        f.type(CTRL_Q + "x");
        assertEquals("Find", f.editor.dialogTitle());
        f.type(ESC);
        f.type(CTRL_S); // Ctrl-S is no longer bound to save, and not typed
        assertFalse(f.editor.dialogOpen());
        f.type("x" + "\u0017"); // Ctrl-W saves, which asks for a name
        assertEquals("Save As", f.editor.dialogTitle());
    }

    @Test
    void statusBarShowsCurrentKeys() {
        var keys = EditorFixture.keysWith("help", "F2", "menu", "F9");
        var f = new EditorFixture(dir, keys, EditorOptions.defaults().withNoColor(true));
        assertTrue(f.status().contains("F2=Help"));
        assertTrue(f.status().contains("F9=Menu"));
        assertTrue(f.status().contains("F3=Repeat Find"));
        assertTrue(f.status().contains("Ln 1 Col 1"));
    }

    @Test
    void statusBarShowsLineColumnAndSyntaxName() {
        var f = new EditorFixture(dir);
        f.type("ab" + ENTER + "cde");
        assertTrue(f.status().contains("Ln 2 Col 4"));
        assertTrue(f.status().contains("Plain Text"));
    }

    @Test
    void cutCopyPasteShowActiveAliasKeys() {
        var f = new EditorFixture(dir);
        f.type("abc");
        f.type(ALT_E);
        assertFalse(f.screenHas("Ctrl-X"));
        f.type(ESC + SHIFT_LEFT + ALT_E);
        assertTrue(f.screenHas("Ctrl-X"));
        assertTrue(f.screenHas("Ctrl-C"));
    }

    @Test
    void menusByMouse() {
        var f = new EditorFixture(dir);
        f.type("hello");
        // The Edit title is the second title of the bar.
        var bar = f.screen().get(0);
        var x = bar.indexOf("Edit") + 1;
        f.click(x, 0);
        assertTrue(f.editor.menuOpen());
        assertTrue(f.screenHas("Select All"));
        // Click Select All: the item row is the seventh row of the drop-down box.
        var rows = f.screen();
        int row = -1;
        int col = -1;
        for (var i = 0; i < rows.size(); i++) {
            var at = rows.get(i).indexOf("Select All");
            if (at >= 0) {
                row = i;
                col = at;
            }
        }
        f.click(col + 2, row);
        assertFalse(f.editor.menuOpen());
        assertEquals("hello", f.editor.selectedText());
    }

    @Test
    void clickOutsideAMenuClosesIt() {
        var f = new EditorFixture(dir);
        f.type("hello");
        f.type(F10);
        f.click(60, 15);
        assertFalse(f.editor.menuOpen());
        assertEquals("hello", f.editor.text());
    }

    @Test
    void clickOnAnotherTitleSwitchesMenu() {
        var f = new EditorFixture(dir);
        f.type(F10);
        var bar = f.screen().get(0);
        f.click(bar.indexOf("Search") + 1, 0);
        assertTrue(f.screenHas("Go To Line"));
        f.click(bar.indexOf("Search") + 1, 0);
        assertFalse(f.editor.menuOpen());
    }

    @Test
    void viewMenuTogglesShowCheckMarks() {
        var f = new EditorFixture(dir);
        f.type(ALT_V);
        f.type("l"); // Line Numbers
        assertTrue(f.editor.options().lineNumbers());
        f.type(ALT_V);
        assertTrue(f.screenHas("✓ Line Numbers") || f.screenHas("✓"));
        f.type(ESC);
        f.type(ALT_V + "w");
        assertTrue(f.editor.options().wrap());
        f.type(ALT_V + "h");
        assertTrue(f.editor.options().showWhitespace());
    }

    @Test
    void optionsMenuTabWidthDialog() {
        var f = new EditorFixture(dir);
        f.type("\u001bo" + "t");
        assertEquals("Tab Width", f.editor.dialogTitle());
        f.type(BACKSPACE + "8" + ENTER);
        assertEquals(8, f.editor.options().tabWidth());
        f.type(TAB);
        assertEquals("        ", f.editor.text());
        f.type("\u001bo" + "u");
        assertFalse(f.editor.options().useSpaces());
    }

    @Test
    void dialogsMoveFocusWithTabAndCancelWithEscape() {
        var f = new EditorFixture(dir);
        f.type(CTRL_Q + "f");
        assertTrue(f.editor.dialogOpen());
        assertTrue(f.screenHas("Find what:"));
        assertTrue(f.screenHas("Match case"));
        assertTrue(f.screenHas("< OK >"));
        assertTrue(f.screenHas("< Cancel >"));
        f.type("abc" + ESC);
        assertFalse(f.editor.dialogOpen());
        assertEquals("", f.editor.text());
    }

    @Test
    void dialogTextFieldEditing() {
        var f = new EditorFixture(dir);
        f.type("needle");
        f.type(CTRL_HOME + CTRL_Q + "f");
        f.type("xyz" + LEFT + LEFT + BACKSPACE + DEL + "Q" + HOME + "<" + END + ">");
        f.type(ENTER);
        assertTrue(f.status().contains("Not found: <Qz>"), f.status());
    }

    @Test
    void dialogButtonsByMouse() {
        var f = new EditorFixture(dir);
        f.type("one two three");
        f.type(CTRL_HOME + CTRL_Q + "f");
        f.type("two");
        var rows = f.screen();
        int row = -1;
        int col = -1;
        for (var i = 0; i < rows.size(); i++) {
            var at = rows.get(i).indexOf("< OK >");
            if (at >= 0) {
                row = i;
                col = at;
            }
        }
        f.click(col + 3, row);
        assertFalse(f.editor.dialogOpen());
        assertEquals("two", f.editor.selectedText());
    }

    @Test
    void dialogCancelButtonByMouse() {
        var f = new EditorFixture(dir);
        f.type(CTRL_Q + "f");
        var rows = f.screen();
        int row = -1;
        int col = -1;
        for (var i = 0; i < rows.size(); i++) {
            var at = rows.get(i).indexOf("< Cancel >");
            if (at >= 0) {
                row = i;
                col = at;
            }
        }
        f.click(col + 4, row);
        assertFalse(f.editor.dialogOpen());
    }

    @Test
    void dialogFieldAndCheckBoxByMouse() {
        var f = new EditorFixture(dir);
        f.type("Foo foo");
        f.type(CTRL_HOME + CTRL_Q + "f");
        var rows = f.screen();
        int checkRow = -1;
        int checkCol = -1;
        int fieldRow = -1;
        int fieldCol = -1;
        for (var i = 0; i < rows.size(); i++) {
            var at = rows.get(i).indexOf("[ ] Match case");
            if (at >= 0) {
                checkRow = i;
                checkCol = at;
            }
            var field = rows.get(i).indexOf("Find what:");
            if (field >= 0) {
                fieldRow = i;
                fieldCol = field + "Find what:".length() + 1;
            }
        }
        f.click(checkCol + 2, checkRow);
        assertTrue(f.screenHas("[x] Match case"));
        f.click(fieldCol + 2, fieldRow);
        f.type("foo" + ENTER);
        assertEquals(7, f.editor.cursor());
    }

    @Test
    void dialogIsModalForMouseClicksOutside() {
        var f = new EditorFixture(dir);
        f.type("hello");
        f.type(CTRL_Q + "f");
        f.click(1, 3);
        assertTrue(f.editor.dialogOpen());
        f.click(1, 0);
        assertTrue(f.editor.dialogOpen());
        assertFalse(f.editor.menuOpen());
    }

    @Test
    void helpDialogListsTheKeysAndCanScrollWithTheWheel() {
        var f = new EditorFixture(dir);
        f.type(F1);
        assertEquals("Keys", f.editor.dialogTitle());
        assertTrue(f.screenHas("Ctrl-S"));
        var before = f.screenJoined();
        f.wheel(40, 12, false);
        assertFalse(before.equals(f.screenJoined()));
        f.type(ESC);
        assertFalse(f.editor.dialogOpen());
    }

    @Test
    void aboutDialog() {
        var f = new EditorFixture(dir);
        f.type("\u001bh" + "a");
        assertEquals("About the Editor", f.editor.dialogTitle());
        f.type(ENTER);
        assertFalse(f.editor.dialogOpen());
    }

    @Test
    void menuKeysDoNotLeakWhileADialogIsOpen() {
        var f = new EditorFixture(dir);
        f.type(CTRL_Q + "f");
        f.type(F10);
        assertFalse(f.editor.menuOpen());
        assertTrue(f.editor.dialogOpen());
    }

    @Test
    void mouseClickOnMenuBarIsIgnoredWhenThereIsNoTitle() {
        var f = new EditorFixture(dir);
        f.mouse(MouseEvent.Type.Pressed, MouseEvent.Button.Button1, 75, 0);
        assertFalse(f.editor.menuOpen());
    }
}
