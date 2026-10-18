package org.github.lscoughlin.jeorje.ui.editor;

import static org.github.lscoughlin.jeorje.ui.editor.EditorFixture.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.github.lscoughlin.jeorje.ui.syntax.HighlightOptions;
import org.github.lscoughlin.jeorje.ui.syntax.HighlightService;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxCatalog;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyntaxTest {

    @TempDir
    Path dir;

    private static SyntaxCatalog catalog;

    @BeforeAll
    static void loadCatalog(@TempDir Path cache) {
        catalog = SyntaxCatalog.load(
                new SyntaxSettings(null, null, List.of(), List.of(), true, cache.resolve("syntax"), "editor-test"));
    }

    private EditorFixture colored(HighlightOptions highlightOptions) {
        var options = EditorOptions.defaults().withNoColor(false);
        return new EditorFixture(dir, EditorFixture.keysWith(), options, catalog, new HighlightService(highlightOptions));
    }

    private static HighlightOptions on() {
        return HighlightOptions.defaults().withNoColor(false);
    }

    /** The style of the cell at a column of a screen row. */
    private static Object style(EditorFixture f, int row, int column) {
        return f.editor.render().get(row).styleAt(column);
    }

    @Test
    void javaKeywordsGetColors() throws IOException {
        var f = colored(on());
        f.write("Main.java", "public class Foo {}\n");
        f.editor.open(dir.resolve("Main.java"));
        assertEquals("java", f.editor.syntaxName().toLowerCase());
        assertTrue(f.status().toLowerCase().contains("java"));
        // the keyword and the plain space between two words differ in style
        assertNotEquals(style(f, 2, 1), style(f, 2, 7));
        assertTrue(f.screenHas("public class Foo {}"));
    }

    @Test
    void plainTextFilesHaveNoSyntaxColors() throws IOException {
        var f = colored(on());
        f.write("notes.zzz", "public class Foo {}\n");
        f.editor.open(dir.resolve("notes.zzz"));
        assertEquals("Plain Text", f.editor.syntaxName());
        assertEquals(style(f, 2, 1), style(f, 2, 7));
    }

    @Test
    void withoutAColorPaletteTheTextHasNoColors() throws IOException {
        var options = EditorOptions.defaults().withNoColor(true);
        var f = new EditorFixture(dir, EditorFixture.keysWith(), options, catalog, new HighlightService(on()));
        f.write("Main.java", "public class Foo {}\n");
        f.editor.open(dir.resolve("Main.java"));
        assertEquals(style(f, 2, 1), style(f, 2, 7));
        assertTrue(f.status().toLowerCase().contains("java"));
    }

    @Test
    void syntaxColorsCanBeTurnedOffAndOn() throws IOException {
        var f = colored(on());
        f.write("Main.java", "public class Foo {}\n");
        f.editor.open(dir.resolve("Main.java"));
        var colored = style(f, 2, 1);
        f.type("\u001bo" + "c"); // Options > Syntax Colors
        assertFalse(f.editor.options().syntaxColors());
        assertEquals(style(f, 2, 1), style(f, 2, 7));
        f.type("\u001bo" + "c");
        assertEquals(colored, style(f, 2, 1));
    }

    @Test
    void syntaxMenuChoosesASyntaxForTheFileAndAutoReturns() throws IOException {
        var f = colored(on());
        f.write("script.zzz", "def foo():\n    return None\n");
        f.editor.open(dir.resolve("script.zzz"));
        assertEquals("Plain Text", f.editor.syntaxName());
        f.type("\u001bo" + "s");
        assertEquals("Syntax", f.editor.dialogTitle());
        assertTrue(f.screenHas("Auto"));
        var name = catalog.byName("python").map(i -> i.name()).orElseThrow();
        f.type(ESC);
        chooseSyntax(f, name);
        assertEquals(name, f.editor.syntaxName());
        assertNotEquals(style(f, 2, 1), style(f, 2, 4));
        // Auto returns to the choice by file name
        f.type("\u001bo" + "s");
        f.type(HOME);
        f.type(ENTER);
        assertEquals("Plain Text", f.editor.syntaxName());
    }

    /** Opens Options > Syntax and moves to the entry with the given name. */
    private void chooseSyntax(EditorFixture f, String name) {
        var names = new java.util.ArrayList<String>();
        names.add("Auto");
        catalog.syntaxes().stream().map(i -> i.name())
                .sorted(java.util.Comparator.comparing(n -> n.toLowerCase(java.util.Locale.ROOT)))
                .forEach(names::add);
        var target = names.indexOf(name);
        assertTrue(target > 0);
        f.type("\u001bo" + "s");
        for (var i = 0; i < target; i++) {
            f.type(DOWN);
        }
        f.type(ENTER);
        assertFalse(f.editor.dialogOpen());
    }

    @Test
    void syntaxMenuWorksWithTheMouseWheelWithoutChangingTheSelection() {
        var f = colored(on());
        f.type("\u001bo" + "s");
        var before = f.screenJoined();
        f.wheel(40, 12, false);
        f.wheel(40, 12, false);
        assertNotEquals(before, f.screenJoined());
        f.type(ENTER); // Auto is still selected
        assertEquals("Plain Text", f.editor.syntaxName());
    }

    @Test
    void largeFilesGetNoColorsAndANotice() throws IOException {
        var f = colored(on().withMaxBytes(10));
        f.write("Big.java", "public class Foo { int x; }\n");
        f.editor.open(dir.resolve("Big.java"));
        assertTrue(f.status().contains("Syntax colors are off"), f.status());
        assertEquals(style(f, 2, 1), style(f, 2, 7));
    }

    @Test
    void editsKeepTheColorsCurrent() throws IOException {
        var f = colored(on());
        f.write("Main.java", "int a;\n");
        f.editor.open(dir.resolve("Main.java"));
        f.type(CTRL_END + "/* open" + ENTER + "still comment");
        var commentStyle = style(f, 3, 3);
        assertNotEquals(style(f, 2, 1), commentStyle);
        f.type(CTRL_HOME + END + ENTER);
        assertTrue(f.screenHas("still comment"));
    }

    @Test
    void saveAsChoosesTheSyntaxOfTheNewName() throws IOException {
        var f = colored(on());
        f.type("public class A {}" + CTRL_S);
        assertEquals("Plain Text", f.editor.syntaxName());
        f.type("A.java" + ENTER);
        assertEquals("java", f.editor.syntaxName().toLowerCase());
    }

    @Test
    void typeAheadSelectsAnEntryThatStartsWithTheLetter() {
        var f = colored(on());
        f.type("\u001bo" + "s");
        f.type("p" + ENTER);
        assertTrue(f.editor.syntaxName().toLowerCase().startsWith("p"), f.editor.syntaxName());
    }
}
