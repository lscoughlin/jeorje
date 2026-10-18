package org.github.lscoughlin.jeorje.ui.chat;

import static org.github.lscoughlin.jeorje.ui.chat.ChatTestSupport.CONFIG;
import static org.github.lscoughlin.jeorje.ui.chat.ChatTestSupport.colored;
import static org.github.lscoughlin.jeorje.ui.chat.ChatTestSupport.tinted;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResultRenderersTest {

    @TempDir
    Path tmp;

    /** True when the text after the number column has colors. */
    private static boolean textColored(org.jline.utils.AttributedString line) {
        var tab = line.toString().indexOf('\t');
        return colored(line.substring(tab + 1, line.length()));
    }

    private ResultRenderers renderers(ChatHighlightConfig config) {
        return new ResultRenderers(ChatTestSupport.context(tmp, config));
    }

    private static final String EDIT = "Edited Foo.java\n--- a/Foo.java\n+++ b/Foo.java\n@@ -1 +1 @@\n-int a;\n+int b;\n";

    @Test
    void editResultIsADiff() {
        var lines = renderers(CONFIG).render("t1", "edit", "Foo.java", EDIT);
        assertEquals(7 - 1, lines.size());
        assertFalse(colored(lines.get(0)));
        assertTrue(tinted(lines.get(4)));
        assertTrue(tinted(lines.get(5)));
    }

    @Test
    void readResultKeepsNumbersOutsideTheHighlightedText() {
        var factory = new ChatTestSupport.RecordingFactory();
        var r = new ResultRenderers(ChatTestSupport.context(tmp, CONFIG, factory));
        var lines = r.render("t2", "read", "src/Foo.java", "     1\tpublic class Foo {\n     2\t}\n");
        assertEquals("     1\tpublic class Foo {", lines.get(0).toString());
        assertEquals(java.util.List.of("public class Foo {", "}"), factory.created.get(0).lines);
        assertTrue(textColored(lines.get(0)));
    }

    @Test
    void readResultOfUnknownFileTypeIsPlain() {
        var lines = renderers(CONFIG).render("t3", "read", "notes.unknownext", "     1\thello world\n");
        assertFalse(textColored(lines.get(0)));
        assertEquals("     1\thello world", lines.get(0).toString());
    }

    @Test
    void readResultOfMarkdownAndLlmsTxt() {
        var r = renderers(CONFIG);
        assertTrue(textColored(r.render("a", "read", "README.md", "     1\t# Title\n").get(0)));
        assertTrue(textColored(r.render("b", "read", "llms.txt", "     1\t# Title\n").get(0)));
    }

    @Test
    void grepResultColorsTheTextPart() {
        var factory = new ChatTestSupport.RecordingFactory();
        var r = new ResultRenderers(ChatTestSupport.context(tmp, CONFIG, factory));
        var lines = r.render("t4", "grep", null,
                "src/Foo.java:12:int a = 1;\nsrc/Bar.java:3:int b;\nREADME.unknownext:4:hello\nFoo.java:5\n");
        assertEquals("src/Foo.java:12:int a = 1;", lines.get(0).toString());
        assertEquals("README.unknownext:4:hello", lines.get(2).toString());
        assertEquals("Foo.java:5", lines.get(3).toString());
        var javaRecorder = factory.created.get(0);
        assertEquals(java.util.List.of("int a = 1;", "int b;"), javaRecorder.lines);
        assertTrue(javaRecorder.resets >= 2, "state is cleared for each line");
    }

    @Test
    void bashAndWebfetchDiffOutputUsesDiffRendering() {
        var r = renderers(CONFIG);
        var out = r.render("t5", "bash", null, "diff --git a/Foo.java b/Foo.java\n--- a/Foo.java\n+++ b/Foo.java\n@@ -1 +1 @@\n-a\n+b\n");
        assertTrue(tinted(out.get(5)));
        var web = r.render("t6", "webfetch", null, "--- a/x.py\n+++ b/x.py\n@@ -1 +1 @@\n-a\n+b\n");
        assertTrue(tinted(web.get(4)));
    }

    @Test
    void otherOutputStaysPlain() {
        var r = renderers(CONFIG);
        for (var tool : new String[] {"bash", "webfetch", "ls", "glob", "unknown"}) {
            var lines = r.render("k" + tool, tool, "Foo.java", "public class Foo {}\n- item\n+ item\n");
            for (var line : lines) {
                assertFalse(colored(line), tool);
            }
        }
    }

    @Test
    void switchesTurnRenderingOff() {
        var read = "     1\tpublic class Foo {}\n";
        assertFalse(textColored(renderers(CONFIG.withRead(false)).render("a", "read", "Foo.java", read).get(0)));
        assertFalse(textColored(renderers(CONFIG.withEnabled(false)).render("b", "read", "Foo.java", read).get(0)));
        assertFalse(textColored(renderers(CONFIG.withNoColor(true)).render("c", "read", "Foo.java", read).get(0)));
        assertFalse(tinted(renderers(CONFIG.withDiff(false)).render("d", "edit", "Foo.java", EDIT).get(5)));
        assertFalse(tinted(renderers(CONFIG.withEnabled(false)).render("e", "edit", "Foo.java", EDIT).get(5)));
    }

    @Test
    void limitsLinesAndAddsANote() {
        var big = IntStream.rangeClosed(1, 2005).mapToObj(i -> String.format("%6d\tint v%d;", i, i))
                .collect(Collectors.joining("\n"));
        var lines = renderers(CONFIG).render("big", "read", "Foo.java", big);
        assertEquals(2006, lines.size());
        assertTrue(textColored(lines.get(1999)));
        assertEquals("[Colors stop after 2000 lines.]", lines.get(2000).toString());
        assertFalse(textColored(lines.get(2001)));
    }

    @Test
    void cachesPerBlockAndKeepsStylesAfterResize() {
        var r = renderers(CONFIG);
        var first = r.render("blk", "edit", "Foo.java", EDIT);
        var second = r.render("blk", "edit", "Foo.java", EDIT);
        assertSame(first, second);
        assertEquals(1, r.cacheSize());
        var wide = ResultRenderers.wrap(first, 80);
        var narrow = ResultRenderers.wrap(first, 6);
        assertEquals(first.size(), wide.size());
        assertTrue(narrow.size() > first.size());
        assertTrue(narrow.stream().anyMatch(ChatTestSupport::tinted), "styles survive the wrap");
        assertSame(first, r.render("blk", "edit", "Foo.java", EDIT));
    }

    @Test
    void slowHighlighterIsTurnedOffAfterTwoSlowPasses() {
        var ticks = new long[] {0};
        var factory = new ChatTestSupport.RecordingFactory() {
            @Override
            public org.github.lscoughlin.jeorje.ui.syntax.LineHighlighter create(
                    org.github.lscoughlin.jeorje.ui.syntax.SyntaxInfo syntax) {
                var inner = super.create(syntax);
                return new org.github.lscoughlin.jeorje.ui.syntax.LineHighlighter() {
                    public org.jline.utils.AttributedString highlight(String line) {
                        ticks[0] += 500_000_000L;
                        return inner.highlight(line);
                    }

                    public void reset() {
                    }
                };
            }
        };
        var config = CONFIG.withBudgetMs(200);
        var ctx = new HighlightContext(config, new LanguageResolver(ChatTestSupport.catalog(tmp), config.aliases()),
                factory, () -> ticks[0]);
        var r = new ResultRenderers(ctx);
        var lines = r.render("slow", "read", "Foo.java", "     1\ta\n     2\tb\n     3\tc\n     4\td\n");
        assertEquals(2, factory.created.get(0).lines.size());
        assertFalse(textColored(lines.get(3)));
        assertFalse(ctx.takeNotices().isEmpty());
    }

    @Test
    void failingHighlighterGivesPlainLines() {
        HighlightContext ctx;
        var config = CONFIG;
        ctx = new HighlightContext(config, new LanguageResolver(ChatTestSupport.catalog(tmp), config.aliases()),
                syntax -> new org.github.lscoughlin.jeorje.ui.syntax.LineHighlighter() {
                    public org.jline.utils.AttributedString highlight(String line) {
                        throw new IllegalStateException("boom");
                    }

                    public void reset() {
                    }
                });
        var lines = new ResultRenderers(ctx).render("x", "read", "Foo.java", "     1\tint a;\n");
        assertEquals("     1\tint a;", lines.get(0).toString());
    }

    @Test
    void longLinesAreCutForTheHighlighter() {
        var factory = new ChatTestSupport.RecordingFactory();
        var config = CONFIG.withMaxLineChars(10);
        var r = new ResultRenderers(ChatTestSupport.context(tmp, config, factory));
        var lines = r.render("long", "read", "Foo.java", "     1\t" + "x".repeat(50) + "\n");
        assertEquals(10, factory.created.get(0).lines.get(0).length());
        assertEquals("     1\t" + "x".repeat(50), lines.get(0).toString());
    }

    @Test
    void configReadsTheKeys() {
        var config = ChatHighlightConfig.fromMap(java.util.Map.of("enabled", true, "maxLines", 50, "diff", false,
                "read", false, "fences", false, "aliases", java.util.Map.of("KTS", "kotlin")));
        assertEquals(50, config.maxLines());
        assertFalse(config.diff());
        assertFalse(config.read());
        assertFalse(config.fences());
        assertEquals("kotlin", config.aliases().get("kts"));
    }
}
