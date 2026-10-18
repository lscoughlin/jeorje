package org.github.lscoughlin.jeorje.ui.editor.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.github.lscoughlin.jeorje.ui.chat.LanguageResolver;
import org.github.lscoughlin.jeorje.ui.syntax.HighlighterFactory;
import org.github.lscoughlin.jeorje.ui.syntax.LineHighlighter;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxCatalog;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxInfo;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxSettings;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownHighlighterTest {

    @TempDir
    Path tmp;

    SyntaxCatalog catalog;
    LanguageResolver resolver;
    SyntaxInfo markdown;

    @BeforeEach
    void setUp() {
        catalog = SyntaxCatalog.load(
                new SyntaxSettings(null, null, List.of(), List.of(), true, tmp.resolve("cache"), "md"));
        resolver = new LanguageResolver(catalog, Map.of());
        markdown = catalog.byName("markdown").orElseThrow();
    }

    /** Fake highlighters. Each one records its lines and resets. */
    static final class Recording implements HighlighterFactory {
        final List<Recorder> created = new ArrayList<>();

        @Override
        public LineHighlighter create(SyntaxInfo syntax) {
            var recorder = new Recorder(syntax.name());
            created.add(recorder);
            return recorder;
        }

        List<Recorder> of(String name) {
            return created.stream().filter(r -> r.syntax.equalsIgnoreCase(name)).toList();
        }
    }

    static final class Recorder implements LineHighlighter {
        final String syntax;
        final List<String> lines = new ArrayList<>();
        int resets;

        Recorder(String syntax) {
            this.syntax = syntax;
        }

        @Override
        public AttributedString highlight(String line) {
            lines.add(line);
            return new AttributedString(line, AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW));
        }

        @Override
        public void reset() {
            resets++;
            lines.add("<reset>");
        }
    }

    private MarkdownHighlighter highlighter(HighlighterFactory factory, InjectionOptions options, String... lines) {
        var h = new MarkdownHighlighter(markdown, resolver, factory, options);
        h.load(List.of(lines));
        return h;
    }

    private static boolean colored(AttributedString s) {
        return s.toAnsi().contains("\u001b[");
    }

    @Test
    void textIsNeverChanged() {
        var h = highlighter(HighlighterFactory.jline(), InjectionOptions.defaults().withNoColor(false),
                "# Title", "```java", "public class A {}", "```", "text");
        var out = h.lines(0, 4);
        assertEquals(List.of("# Title", "```java", "public class A {}", "```", "text"),
                out.stream().map(AttributedString::toString).toList());
    }

    @Test
    void javaBlockGetsJavaColorsAndTextGetsMarkdownColors() throws Exception {
        var options = InjectionOptions.defaults().withNoColor(false);
        var h = highlighter(HighlighterFactory.jline(), options, "```java", "public class A {}", "```");
        var expected = HighlighterFactory.jline().create(catalog.byName("java").orElseThrow())
                .highlight("public class A {}");
        assertEquals(expected.toAnsi(), h.lines(1, 1).getFirst().toAnsi());
        assertTrue(colored(h.lines(1, 1).getFirst()));
    }

    @Test
    void languageWordHasItsOwnColorAndUnknownWordIsDim() {
        var rec = new Recording();
        var h = highlighter(rec, InjectionOptions.defaults().withNoColor(false),
                "```java", "x", "```", "```nosuchlang", "y", "```", "```", "z", "```");
        var known = h.lines(0, 0).getFirst();
        var unknown = h.lines(3, 3).getFirst();
        assertEquals(MarkdownHighlighter.LANGUAGE, known.styleAt(3));
        assertEquals(MarkdownHighlighter.MARKER, known.styleAt(0));
        assertEquals(MarkdownHighlighter.DIM, unknown.styleAt(5));
        // content of an unknown block and of a block with no tag: one dim color, and no highlighter
        assertEquals(MarkdownHighlighter.DIM, h.lines(4, 4).getFirst().styleAt(0));
        assertEquals(MarkdownHighlighter.DIM, h.lines(7, 7).getFirst().styleAt(0));
        assertEquals(MarkdownHighlighter.MARKER, h.lines(2, 2).getFirst().styleAt(0));
        assertEquals(0, rec.of("nosuchlang").size());
    }

    @Test
    void braceFormsResolve() {
        var rec = new Recording();
        var h = highlighter(rec, InjectionOptions.defaults().withNoColor(false),
                "```{java}", "a", "```", "```{.py}", "b", "```", "```{r, echo=FALSE}", "c", "```");
        h.lines(0, 8);
        assertEquals(1, rec.of("java").size());
        assertEquals(1, rec.of("py").size() + rec.of("python").size());
    }

    @Test
    void eachBlockStartsWithReset() {
        var rec = new Recording();
        var h = highlighter(rec, InjectionOptions.defaults().withNoColor(false),
                "```java", "a", "```", "text", "```java", "b", "```");
        h.lines(0, 6);
        assertEquals(1, rec.of("java").size(), "one highlighter for each language");
        var lines = rec.of("java").getFirst().lines;
        assertEquals(List.of("<reset>", "a", "<reset>", "b"), lines);
    }

    @Test
    void multiLineStateDoesNotLeakBetweenBlocksOrIntoText() throws Exception {
        var options = InjectionOptions.defaults().withNoColor(false);
        var h = highlighter(HighlighterFactory.jline(), options,
                "```java", "/* open comment", "```", "```java", "int x = 1;", "```", "plain text");
        var fresh = HighlighterFactory.jline().create(catalog.byName("java").orElseThrow());
        assertEquals(fresh.highlight("int x = 1;").toAnsi(), h.lines(4, 4).getFirst().toAnsi());
        var freshMd = HighlighterFactory.jline().create(markdown);
        assertEquals(freshMd.highlight("plain text").toAnsi(), h.lines(6, 6).getFirst().toAnsi());
    }

    @Test
    void markdownTextDoesNotSeeBlockContent() {
        var rec = new Recording();
        var h = highlighter(rec, InjectionOptions.defaults().withNoColor(false),
                "# a", "```java", "# not a heading", "```", "# b");
        h.lines(0, 4);
        var md = rec.of("markdown");
        assertEquals(1, md.size());
        assertEquals(List.of("<reset>", "# a", "# b"), md.getFirst().lines);
    }

    @Test
    void typingAFenceChangesColorsBelowAtOnce() {
        var options = InjectionOptions.defaults().withNoColor(false);
        var h = highlighter(HighlighterFactory.jline(), options, "intro", "public class A {}", "more");
        var before = h.lines(1, 1).getFirst().toAnsi();
        h.replace(1, 0, List.of("```java"));
        var after = h.lines(2, 2).getFirst().toAnsi();
        assertNotEquals(before, after);
        h.replace(3, 0, List.of("```"));
        h.replace(4, 0, List.of("public class A {}"));
        assertEquals(before, h.lines(4, 4).getFirst().toAnsi(), "closed fence: Markdown colors return");
    }

    @Test
    void frontMatterUsesYaml() {
        var rec = new Recording();
        var h = highlighter(rec, InjectionOptions.defaults().withNoColor(false),
                "---", "title: A", "---", "# Head");
        h.lines(0, 3);
        var yaml = rec.of("yaml");
        assertEquals(1, yaml.size());
        assertEquals(List.of("<reset>", "---", "title: A", "---"), yaml.getFirst().lines);
        var none = new Recording();
        highlighter(none, InjectionOptions.defaults().withNoColor(false), "---", "words", "---").lines(0, 2);
        assertEquals(0, none.of("yaml").size());
    }

    @Test
    void offSwitchUsesMarkdownColorsOnly() {
        var rec = new Recording();
        var options = InjectionOptions.fromConfig(Map.of("editor.syntax.injections", false)).withNoColor(false);
        assertFalse(options.enabled());
        var h = highlighter(rec, options, "```java", "int x;", "```", "---", "a: 1", "---");
        assertFalse(h.appliesTo(markdown));
        var out = h.lines(0, 5);
        assertEquals(6, out.size());
        assertEquals(0, rec.of("java").size());
        assertEquals(0, rec.of("yaml").size());
        assertEquals(List.of("<reset>", "```java", "int x;", "```", "---", "a: 1", "---"),
                rec.of("markdown").getFirst().lines);
        h.replace(1, 1, List.of("int y;"));
        assertEquals("int y;", h.lines(1, 1).getFirst().toString());
        assertEquals(6, h.lineCount());
    }

    @Test
    void configKeyDefaultsToOn() {
        assertTrue(InjectionOptions.fromConfig(Map.of()).enabled());
        assertFalse(InjectionOptions.fromConfig(Map.of("editor.syntax.injections", "false")).enabled());
        assertTrue(new MarkdownHighlighter(markdown, resolver, new Recording(), InjectionOptions.defaults())
                .appliesTo(markdown));
        assertFalse(new MarkdownHighlighter(markdown, resolver, new Recording(), InjectionOptions.defaults())
                .appliesTo(catalog.byName("java").orElseThrow()));
    }

    @Test
    void longBlockReplaysFromBeforeTheVisiblePart() {
        var rec = new Recording();
        var lines = new ArrayList<String>();
        lines.add("```java");
        for (var i = 1; i <= 3000; i++) {
            lines.add("L" + i);
        }
        lines.add("```");
        var h = new MarkdownHighlighter(markdown, resolver, rec,
                InjectionOptions.defaults().withNoColor(false).withReplayLines(100));
        h.load(lines);
        h.lines(2000, 2004);
        var seen = rec.of("java").getFirst().lines;
        assertEquals(List.of("<reset>", "L1900"), seen.subList(0, 2));
        assertEquals("L2004", seen.getLast());
        assertEquals(106, seen.size());
    }

    @Test
    void hugeBlockIsHighlightedFromTheVisiblePartOnly() {
        var rec = new Recording();
        var lines = new ArrayList<String>();
        lines.add("```java");
        for (var i = 1; i <= 6000; i++) {
            lines.add("L" + i);
        }
        lines.add("```");
        var h = new MarkdownHighlighter(markdown, resolver, rec,
                InjectionOptions.defaults().withNoColor(false).withReplayLines(100));
        h.load(lines);
        var out = h.lines(5000, 5003);
        assertEquals(4, out.size());
        assertTrue(colored(out.getFirst()));
        var seen = rec.of("java").getFirst().lines;
        assertEquals(List.of("<reset>", "L5000", "L5001", "L5002", "L5003"), seen.subList(seen.size() - 5, seen.size()));
        assertFalse(seen.contains("L4950"));
    }

    @Test
    void hugeBlockShownFromItsStartIsReset() {
        var rec = new Recording();
        var lines = new ArrayList<String>();
        lines.add("```java");
        for (var i = 1; i <= 6000; i++) {
            lines.add("L" + i);
        }
        var h = new MarkdownHighlighter(markdown, resolver, rec, InjectionOptions.defaults().withNoColor(false));
        h.load(lines);
        h.lines(0, 3);
        assertEquals(List.of("<reset>", "L1", "L2", "L3"), rec.of("java").getFirst().lines);
    }

    @Test
    void blockQuotePrefixIsNotGivenToTheLanguage() {
        var rec = new Recording();
        var h = highlighter(rec, InjectionOptions.defaults().withNoColor(false),
                "> ```java", "> int x;", "> ```");
        var out = h.lines(1, 1).getFirst();
        assertEquals("> int x;", out.toString());
        assertEquals(List.of("<reset>", "int x;"), rec.of("java").getFirst().lines);
    }

    @Test
    void noColorGivesPlainText() {
        var h = highlighter(HighlighterFactory.jline(), InjectionOptions.defaults().withNoColor(true),
                "```java", "int x;", "```");
        assertFalse(colored(h.lines(0, 2).get(1)));
    }

    @Test
    void failingFactoryFallsBackToDimBlock() {
        HighlighterFactory failing = syntax -> {
            if (syntax.name().equalsIgnoreCase("java")) {
                throw new IllegalStateException("boom");
            }
            return new Recorder(syntax.name());
        };
        var h = highlighter(failing, InjectionOptions.defaults().withNoColor(false), "```java", "x", "```");
        assertEquals(MarkdownHighlighter.DIM, h.lines(1, 1).getFirst().styleAt(0));
    }
}
