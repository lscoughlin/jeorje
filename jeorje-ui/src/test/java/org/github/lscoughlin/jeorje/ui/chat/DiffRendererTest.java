package org.github.lscoughlin.jeorje.ui.chat;

import static org.github.lscoughlin.jeorje.ui.chat.ChatTestSupport.CONFIG;
import static org.github.lscoughlin.jeorje.ui.chat.ChatTestSupport.bold;
import static org.github.lscoughlin.jeorje.ui.chat.ChatTestSupport.colored;
import static org.github.lscoughlin.jeorje.ui.chat.ChatTestSupport.tinted;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiffRendererTest {

    @TempDir
    Path tmp;

    private static final List<String> DIFF = List.of(
            "diff --git a/Foo.java b/Foo.java", "index 1..2 100644", "--- a/Foo.java", "+++ b/Foo.java",
            "@@ -1,3 +1,3 @@", " class Foo {", "-    int a = 1;", "+    int b = 2;", " }");

    @Test
    void headersHunksAndTints() {
        var diff = new DiffRenderer(ChatTestSupport.context(tmp, CONFIG), null);
        var out = diff.renderAll(DIFF);
        for (var i = 0; i < 4; i++) {
            assertTrue(bold(out.get(i), 0), "header " + i);
        }
        assertTrue(colored(out.get(4)));
        assertTrue(out.get(4).toAnsi().contains("36"), "hunk is cyan: " + out.get(4).toAnsi());
        assertFalse(tinted(out.get(5)), "context has no tint");
        assertTrue(tinted(out.get(6)));
        assertTrue(tinted(out.get(7)));
        assertTrue(out.get(6).toAnsi().contains("48;") && out.get(7).toAnsi().contains("48;"));
        assertEquals("-    int a = 1;", out.get(6).toString());
        assertEquals("+    int b = 2;", out.get(7).toString());
        assertEquals(" }", out.get(8).toString());
    }

    @Test
    void contentUsesTheSyntaxOfThePlusHeader() {
        var factory = new ChatTestSupport.RecordingFactory();
        var diff = new DiffRenderer(ChatTestSupport.context(tmp, CONFIG, factory), null);
        diff.renderAll(DIFF);
        assertFalse(factory.created.isEmpty());
        assertTrue(factory.created.stream().anyMatch(r -> r.syntax.equals("Java") && r.lines.contains("    int b = 2;")));
    }

    @Test
    void syntaxFromToolInputHint() {
        var factory = new ChatTestSupport.RecordingFactory();
        var diff = new DiffRenderer(ChatTestSupport.context(tmp, CONFIG, factory), "src/Foo.java");
        diff.line("@@ -1 +1 @@");
        diff.line("-int a;");
        diff.line("+int b;");
        assertTrue(factory.created.stream().allMatch(r -> r.syntax.equals("Java")));
    }

    @Test
    void removedAndAddedLinesUseSeparateStreams() {
        var factory = new ChatTestSupport.RecordingFactory();
        var diff = new DiffRenderer(ChatTestSupport.context(tmp, CONFIG, factory), "Foo.java");
        diff.line("@@ -1,2 +1,2 @@");
        diff.line("-/* open");
        diff.line("+int added;");
        diff.line("-more");
        diff.line("+int again;");
        var removed = factory.created.stream().filter(r -> r.lines.contains("/* open")).findFirst().orElseThrow();
        var added = factory.created.stream().filter(r -> r.lines.contains("int added;")).findFirst().orElseThrow();
        assertTrue(removed != added);
        assertEquals(List.of("/* open", "more"), removed.lines);
        assertEquals(List.of("int added;", "int again;"), added.lines);
    }

    @Test
    void aRemovedLineThatLooksLikeAHeaderStaysContent() {
        var diff = new DiffRenderer(ChatTestSupport.context(tmp, CONFIG), "a.sql");
        diff.line("@@ -1,2 +1,1 @@");
        var removed = diff.line("--- a comment");
        var added = diff.line("+++ b");
        assertTrue(tinted(removed));
        assertTrue(tinted(added));
    }

    @Test
    void secondFileStartsNewStreams() {
        var factory = new ChatTestSupport.RecordingFactory();
        var diff = new DiffRenderer(ChatTestSupport.context(tmp, CONFIG, factory), null);
        diff.renderAll(List.of("--- a/A.java", "+++ b/A.java", "@@ -1 +1 @@", "-/* x", "+y",
                "--- a/B.py", "+++ b/B.py", "@@ -1 +1 @@", "-z", "+w"));
        assertTrue(factory.created.stream().anyMatch(r -> r.syntax.equals("python") && r.lines.contains("w")));
    }

    @Test
    void fewColorsGiveBoldMarkerWithoutTint() {
        var diff = new DiffRenderer(ChatTestSupport.context(tmp, CONFIG.withFewColors(true)), "Foo.java");
        diff.line("@@ -1 +1 @@");
        var removed = diff.line("-int a;");
        assertFalse(tinted(removed));
        assertTrue(bold(removed, 0));
        assertEquals("-int a;", removed.toString());
    }

    @Test
    void noColorGivesBoldMarkerAndNoColors() {
        var diff = new DiffRenderer(ChatTestSupport.context(tmp, CONFIG.withNoColor(true)), "Foo.java");
        var out = diff.renderAll(DIFF);
        for (var line : out) {
            assertFalse(line.toAnsi().matches("(?s).*\u001b\\[[0-9;]*(3[0-7]|4[0-8]|9[0-7])[;m].*"), line.toAnsi());
        }
        assertTrue(bold(out.get(7), 0));
    }

    @Test
    void codeColorsOffKeepsTintsWhenEditorSyntaxIsOff() {
        var diff = new DiffRenderer(ChatTestSupport.context(tmp, CONFIG.withEditorSyntaxEnabled(false)), "Foo.java");
        diff.line("@@ -1 +1 @@");
        var added = diff.line("+public class Foo {}");
        assertTrue(tinted(added));
    }

    @Test
    void diffRenderingOffGivesPlainLines() {
        var diff = new DiffRenderer(ChatTestSupport.context(tmp, CONFIG.withDiff(false)), "Foo.java");
        for (var line : diff.renderAll(DIFF)) {
            assertFalse(colored(line));
        }
    }

    @Test
    void detectsUnifiedDiffs() {
        assertTrue(DiffRenderer.looksLikeDiff("diff --git a/x b/x\n"));
        assertTrue(DiffRenderer.looksLikeDiff("--- a/x\n+++ b/x\n"));
        assertTrue(DiffRenderer.looksLikeDiff("@@ -1,2 +3,4 @@ foo"));
        assertTrue(DiffRenderer.looksLikeDiff("\nnote\n@@ -1 +1 @@\n"));
        assertFalse(DiffRenderer.looksLikeDiff("hello\nworld\n"));
        assertFalse(DiffRenderer.looksLikeDiff("- item\n+ item\n"));
    }

    @Test
    void textOutsideHunksIsPlain() {
        var diff = new DiffRenderer(ChatTestSupport.context(tmp, CONFIG), "Foo.java");
        assertFalse(colored(diff.line("Edited Foo.java")));
        assertFalse(colored(diff.line("+ not in a hunk")));
    }
}
