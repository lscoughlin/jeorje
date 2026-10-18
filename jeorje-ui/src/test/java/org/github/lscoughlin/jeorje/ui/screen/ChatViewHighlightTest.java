package org.github.lscoughlin.jeorje.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.ui.chat.ChatHighlightConfig;
import org.github.lscoughlin.jeorje.ui.chat.HighlightContext;
import org.github.lscoughlin.jeorje.ui.chat.LanguageResolver;
import org.github.lscoughlin.jeorje.ui.syntax.HighlighterFactory;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxCatalog;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxSettings;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatViewHighlightTest {
    private static final AgentId A = new AgentId("a");
    private static final String ESC = "\u001b";

    @TempDir
    Path tmp;

    private ChatView chat(ChatHighlightConfig config) {
        var catalog = SyntaxCatalog.load(
                new SyntaxSettings(null, null, List.of(), List.of(), true, tmp.resolve("cache"), "chat"));
        return new ChatView(new HighlightContext(config, new LanguageResolver(catalog, config.aliases()),
                HighlighterFactory.jline()));
    }

    private static ChatHighlightConfig on() {
        return ChatHighlightConfig.defaults().withNoColor(false);
    }

    private static boolean colored(AttributedString s) {
        return s.toAnsi().contains(ESC);
    }

    private static boolean tinted(AttributedString s) {
        return s.toAnsi().matches("(?s).*\u001b\\[[0-9;]*48;.*");
    }

    private static boolean hasColor(AttributedString s) {
        return s.toAnsi().matches("(?s).*\u001b\\[[0-9;]*(3[0-7]|9[0-7]|38|4[0-7]|48)[;m].*");
    }

    private static int lineOf(List<AttributedString> lines, String text) {
        for (var i = 0; i < lines.size(); i++) if (lines.get(i).toString().contains(text)) return i;
        throw new AssertionError("no line with " + text);
    }

    private static void tool(ChatView chat, String id, String tool, String summary, String output) {
        chat.apply(new AgentEvent.ToolStarted(A, id, tool, summary));
        chat.apply(new AgentEvent.ToolFinished(A, id, ToolResultData.ok(output)));
        chat.toggleResult(id);
    }

    @Test
    void fencedJavaBlockHasStyledKeywords() {
        var chat = chat(on());
        chat.apply(new AgentEvent.TokenDelta(A, "Text\n```java\npublic class X {}\n```\nafter\n"));
        var lines = chat.logicalLines();
        var code = lines.get(lineOf(lines, "public class X"));
        assertTrue(hasColor(code), code.toAnsi());
        assertFalse(hasColor(lines.get(lineOf(lines, "after"))));
    }

    @Test
    void lineInProgressIsPlainUntilNewline() {
        var chat = chat(on());
        chat.apply(new AgentEvent.TokenDelta(A, "```java\npublic class"));
        var lines = chat.logicalLines();
        assertFalse(hasColor(lines.get(lines.size() - 1)));
        chat.apply(new AgentEvent.TokenDelta(A, " X {}\n"));
        lines = chat.logicalLines();
        assertTrue(hasColor(lines.get(lineOf(lines, "public class X"))));
    }

    @Test
    void untaggedNonDiffBlockIsPlain() {
        var chat = chat(on());
        chat.apply(new AgentEvent.TokenDelta(A, "```\n# not a heading public class\nint x = 1;\n```\n"));
        var lines = chat.logicalLines();
        assertFalse(hasColor(lines.get(1)), lines.get(1).toAnsi());
        assertFalse(colored(lines.get(2)));
    }

    @Test
    void untaggedDiffBlockGetsDiffRendering() {
        var chat = chat(on());
        chat.apply(new AgentEvent.TokenDelta(A, "```\n--- a/X.java\n+++ b/X.java\n@@ -1,1 +1,1 @@\n-int a;\n+int b;\n```\n"));
        var lines = chat.logicalLines();
        assertTrue(tinted(lines.get(lineOf(lines, "int b;"))));
        assertTrue(tinted(lines.get(lineOf(lines, "int a;"))));
    }

    @Test
    void editResultHasTintedLinesWithFileSyntax() {
        var chat = chat(on());
        var diff = "--- a/X.java\n+++ b/X.java\n@@ -1,1 +1,1 @@\n-public int a;\n+public int b;\n";
        tool(chat, "t1", "edit", "{\"path\":\"X.java\"}", diff);
        var lines = chat.logicalLines();
        var added = lines.get(lineOf(lines, "public int b;"));
        var removed = lines.get(lineOf(lines, "public int a;"));
        assertTrue(tinted(added));
        assertTrue(tinted(removed));
        assertNotEquals(added.toAnsi().replace("b;", "a;"), removed.toAnsi());
        // The keyword has a foreground color on top of the tint.
        assertTrue(added.toAnsi().matches("(?s).*\u001b\\[[0-9;]*38;.*") || hasColor(added));
    }

    @Test
    void readKeepsTheLineNumberColumnPlain() {
        var chat = chat(on());
        tool(chat, "t1", "read", "{\"path\":\"X.java\"}", "     1\tpublic class X {}\n     2\t}\n");
        var lines = chat.logicalLines();
        var line = lines.get(lineOf(lines, "public class X"));
        var text = line.toString();
        var tab = text.indexOf('\t');
        assertTrue(tab > 0);
        assertFalse(hasColor(line.substring(0, tab + 1)), "number column must have no color");
        assertTrue(hasColor(line.substring(tab + 1, line.length())));
    }

    @Test
    void grepHighlightsOnlyTheTextPart() {
        var chat = chat(on());
        tool(chat, "t1", "grep", "{\"pattern\":\"x\"}", "src/X.java:3:public class X {}\n");
        var lines = chat.logicalLines();
        var line = lines.get(lineOf(lines, "src/X.java:3:"));
        var colon = line.toString().indexOf("src/X.java:3:") + "src/X.java:3:".length();
        assertTrue(hasColor(line.substring(colon, line.length())));
    }

    @Test
    void bashDiffOutputGetsDiffRendering() {
        var chat = chat(on());
        tool(chat, "t1", "bash", "{\"command\":\"git diff\"}",
                "diff --git a/X.java b/X.java\n--- a/X.java\n+++ b/X.java\n@@ -1,1 +1,1 @@\n-int a;\n+int b;\n");
        var lines = chat.logicalLines();
        assertTrue(tinted(lines.get(lineOf(lines, "int b;"))));
    }

    @Test
    void disabledGivesPlainOutput() {
        var chat = chat(on().withEnabled(false));
        chat.apply(new AgentEvent.TokenDelta(A, "```java\npublic class X {}\n```\n"));
        tool(chat, "t1", "edit", "{\"path\":\"X.java\"}", "+++ b/X.java\n@@ -1,1 +1,1 @@\n-int a;\n+int b;\n");
        for (var line : chat.logicalLines()) {
            if (line.toString().startsWith("▶")) continue;
            assertFalse(hasColor(line), line.toAnsi());
        }
    }

    @Test
    void noColorRemovesColors() {
        var chat = chat(on().withNoColor(true));
        chat.appendUser("hi");
        chat.apply(new AgentEvent.TokenDelta(A, "# Title\n```java\npublic class X {}\n```\n"));
        tool(chat, "t1", "edit", "{\"path\":\"X.java\"}", "+++ b/X.java\n@@ -1,1 +1,1 @@\n-int a;\n+int b;\n");
        for (var line : chat.logicalLines()) {
            assertFalse(hasColor(line), line.toAnsi());
        }
    }

    @Test
    void resizeKeepsStyles() {
        var chat = chat(on());
        chat.apply(new AgentEvent.TokenDelta(A, "```java\npublic class VeryLongName {}\n```\n"));
        var wide = chat.wrapped(80);
        var narrow = chat.wrapped(10);
        assertTrue(narrow.size() > wide.size());
        var colored = narrow.stream().filter(ChatViewHighlightTest::hasColor).count();
        assertTrue(colored >= 2, "wrapped pieces keep their colors");
        assertEquals(wide.get(1).toString(), String.join("", narrow.subList(1, narrow.size() - 1).stream()
                .map(AttributedString::toString).toList()));
    }

    @Test
    void exportsHaveNoEscapeCodes() {
        var chat = chat(on());
        chat.apply(new AgentEvent.TokenDelta(A, "```java\npublic class X {}\n```\n"));
        tool(chat, "t1", "edit", "{\"path\":\"X.java\"}", "+++ b/X.java\n@@ -1,1 +1,1 @@\n-int a;\n+int b;\n");
        var text = chat.plainText();
        assertFalse(text.contains(ESC));
        assertTrue(text.contains("public class X {}"));
        assertTrue(text.contains("+int b;"));
    }

    @Test
    void configMapFeedsTheContext() {
        var config = ChatHighlightConfig.fromMap(Map.of("enabled", true, "fences", false)).withNoColor(false);
        var chat = chat(config);
        chat.apply(new AgentEvent.TokenDelta(A, "```java\npublic class X {}\n```\n"));
        var lines = chat.logicalLines();
        assertFalse(hasColor(lines.get(lineOf(lines, "public class X"))));
    }

    @Test
    void screenGivesItsChatViewsTheHighlighting() throws Exception {
        var catalog = SyntaxCatalog.load(
                new SyntaxSettings(null, null, List.of(), List.of(), true, tmp.resolve("c2"), "chat"));
        var config = on();
        var context = new HighlightContext(config, new LanguageResolver(catalog, config.aliases()),
                HighlighterFactory.jline());
        try (var ft = new FakeTerminal(60, 10)) {
            var screen = new Screen(ft.terminal, Screen.Options.defaults(), context);
            screen.apply(new AgentEvent.TokenDelta(new AgentId("main"), "```java\npublic class X {}\n```\n"));
            var lines = screen.chat(new AgentId("main")).logicalLines();
            assertTrue(hasColor(lines.get(lineOf(lines, "public class X"))));
        }
    }
}
