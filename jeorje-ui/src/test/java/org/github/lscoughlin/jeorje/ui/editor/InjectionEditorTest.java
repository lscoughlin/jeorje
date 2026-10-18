package org.github.lscoughlin.jeorje.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.ui.editor.markdown.InjectionOptions;
import org.github.lscoughlin.jeorje.ui.syntax.HighlightOptions;
import org.github.lscoughlin.jeorje.ui.syntax.HighlightService;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxCatalog;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxSettings;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The editor draws Markdown files through the injection layer. */
class InjectionEditorTest {

    private static SyntaxCatalog catalog;

    @TempDir
    Path dir;

    @BeforeAll
    static void loadCatalog(@TempDir Path cache) {
        catalog = SyntaxCatalog.load(
                new SyntaxSettings(null, null, List.of(), List.of(), true, cache.resolve("syntax"), "inject-test"));
    }

    private QEditor editor(InjectionOptions injections, String name, String text) throws IOException {
        var editor = QEditor.builder()
                .baseDir(dir)
                .options(EditorOptions.defaults().withNoColor(false))
                .catalog(catalog)
                .highlight(new HighlightService(HighlightOptions.defaults().withNoColor(false)))
                .injections(injections)
                .build();
        editor.setSize(80, 24);
        java.nio.file.Files.writeString(dir.resolve(name), text);
        editor.open(dir.resolve(name));
        return editor;
    }

    private static InjectionOptions on() {
        return InjectionOptions.defaults().withNoColor(false).withEnabled(true);
    }

    private static InjectionOptions off() {
        return on().withEnabled(false);
    }

    /** The style of a cell of the text window. Text line {@code line} is on screen row {@code line + 2}. */
    private static AttributedStyle style(QEditor editor, int line, int column) {
        return editor.render().get(line + 2).styleAt(column + 1);
    }

    @Test
    void javaFenceGetsJavaColorsAndTextGetsMarkdownColors() throws IOException {
        var md = editor(on(), "a.md", "# Title\n```java\npublic class A {}\n```\ntext\n");
        var java = editor(on(), "A.java", "public class A {}\n");
        assertEquals("markdown", md.syntaxName().toLowerCase());
        // inside the fence: the same styles as in a Java file
        for (var column : new int[] {0, 3, 6, 7, 12}) {
            assertEquals(style(java, 0, column), style(md, 2, column), "column " + column);
        }
        assertNotEquals(style(md, 2, 0), style(md, 2, 6), "the keyword differs from the space");
        // outside the fence: the Markdown colors of the same text
        var plain = editor(off(), "b.md", "# Title\n```java\npublic class A {}\n```\ntext\n");
        assertEquals(style(plain, 0, 0), style(md, 0, 0));
        assertEquals(style(plain, 0, 0), style(md, 0, 3));
        assertTrue(md.render().get(2).toString().contains("# Title"));
    }

    @Test
    void typingAClosingFenceChangesTheColorsBelowAtOnce() throws IOException {
        var f = "```java\npublic static\n\npublic static\n";
        var md = editor(on(), "c.md", f);
        var keywordInFence = style(md, 3, 0);
        assertEquals(keywordInFence, style(md, 1, 0));
        md.feed(EditorFixture.CTRL_HOME + EditorFixture.DOWN + EditorFixture.DOWN + "```");
        assertTrue(md.text().contains("\n```\npublic static"));
        var after = style(md, 3, 0);
        assertNotEquals(keywordInFence, after, "the line below is Markdown now");
        var plain = editor(off(), "d.md", "```java\npublic static\n```\npublic static\n");
        assertEquals(style(plain, 3, 0), after);
        // the line inside the fence keeps its Java colors
        assertEquals(keywordInFence, style(md, 1, 0));
    }

    @Test
    void typingAnOpeningFenceColorsTheContentBelow() throws IOException {
        var md = editor(on(), "e.md", "\npublic static\n");
        var before = style(md, 1, 0);
        md.feed("```java");
        assertNotEquals(before, style(md, 1, 0));
    }

    @Test
    void injectionsOffGivesPlainMarkdownColors() throws IOException {
        var text = "```java\npublic class A {}\n```\n";
        var off = editor(off(), "f.md", text);
        var java = editor(on(), "B.java", "public class A {}\n");
        // the Markdown syntax colors the whole content of the block with one color: no keywords
        for (var column : new int[] {3, 7, 12}) {
            assertEquals(style(off, 1, 0), style(off, 1, column), "column " + column);
        }
        assertNotEquals(style(java, 0, 0), style(off, 1, 0));
        var on = editor(on(), "h.md", text);
        assertEquals(style(java, 0, 0), style(on, 1, 0));
    }

    @Test
    void editsKeepTheTextAndTheLinesInStep() throws IOException {
        var md = editor(on(), "i.md", "a\n```java\nint x;\n```\nb\n");
        md.feed(EditorFixture.CTRL_END + "tail");
        md.feed(EditorFixture.CTRL_HOME + EditorFixture.ENTER + EditorFixture.ENTER);
        md.feed(EditorFixture.CTRL_Z);
        var lines = new ArrayList<String>();
        md.render().forEach(l -> lines.add(l.toString()));
        assertEquals("\na\n```java", md.text().lines().limit(3).collect(java.util.stream.Collectors.joining("\n")));
        assertTrue(md.text().endsWith("b\ntail"));
        assertTrue(lines.stream().anyMatch(l -> l.contains("int x;")));
    }

    @Test
    void externalChangeShowsTheNotice() throws IOException {
        var seen = new ArrayList<String>();
        var editor = QEditor.builder().baseDir(dir).options(EditorOptions.defaults().withNoColor(true))
                .noticeSink(seen::add).build();
        editor.setSize(80, 24);
        java.nio.file.Files.writeString(dir.resolve("j.txt"), "x\n");
        editor.open(dir.resolve("j.txt"));
        editor.externalChange(dir.resolve("other.txt"));
        assertEquals(List.of(), seen, "another file is ignored");
        editor.externalChange(dir.resolve("j.txt"));
        assertTrue(editor.diskChanged());
        assertTrue(editor.statusText().contains(QEditor.CHANGED_BY_AGENT), editor.statusText());
        assertEquals(1, seen.size());
        assertTrue(String.join("\n", editor.screenText()).contains("changed by the agent"));
    }
}
