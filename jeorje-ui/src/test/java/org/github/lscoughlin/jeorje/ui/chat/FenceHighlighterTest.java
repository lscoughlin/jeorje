package org.github.lscoughlin.jeorje.ui.chat;

import static org.github.lscoughlin.jeorje.ui.chat.ChatTestSupport.CONFIG;
import static org.github.lscoughlin.jeorje.ui.chat.ChatTestSupport.colored;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FenceHighlighterTest {

    @TempDir
    Path tmp;

    private FenceHighlighter fence(ChatHighlightConfig config) {
        return new FenceHighlighter(ChatTestSupport.context(tmp, config));
    }

    @Test
    void taggedBlockIsHighlightedAndOtherTextIsPlain() {
        var f = fence(CONFIG);
        f.append("Intro text\n```java\npublic class Foo {}\n```\nAfter\n");
        var lines = f.lines();
        assertEquals("Intro text", lines.get(0).toString());
        assertFalse(colored(lines.get(0)));
        assertTrue(colored(lines.get(2)), "code line");
        assertEquals("public class Foo {}", lines.get(2).toString());
        assertFalse(colored(lines.get(4)));
    }

    @Test
    void streamingKeepsLineInProgressPlainAndHighlightsWhenComplete() {
        var f = fence(CONFIG);
        for (var c : "```java\npublic class Foo {}".toCharArray()) {
            f.append(String.valueOf(c));
        }
        var last = f.lines().get(f.lines().size() - 1);
        assertEquals("public class Foo {}", last.toString());
        assertFalse(colored(last), "line in progress is plain");
        f.append("\n");
        assertTrue(colored(f.lines().get(1)), "complete line has colors");
        // the fence has not closed: more code still highlights
        f.append("int x = 1;\n");
        assertTrue(colored(f.lines().get(2)));
    }

    @Test
    void oneHighlighterForEachBlock() {
        var factory = new ChatTestSupport.RecordingFactory();
        var f = new FenceHighlighter(ChatTestSupport.context(tmp, CONFIG, factory));
        f.append("```java\na\nb\nc\n```\n```java\nd\n```\n");
        assertEquals(2, factory.created.size());
        assertEquals(java.util.List.of("a", "b", "c"), factory.created.get(0).lines);
        assertEquals(java.util.List.of("d"), factory.created.get(1).lines);
    }

    @Test
    void multiLineRegionKeepsItsStateInsideOneBlock() {
        var f = fence(CONFIG);
        f.append("```java\n/* open\nstill comment\nend */\n```\n");
        var lines = f.lines();
        assertTrue(colored(lines.get(2)), "a line inside the comment is colored");
    }

    @Test
    void unknownOrMissingTagGivesPlainText() {
        var f = fence(CONFIG);
        f.append("```text\npublic class Foo {}\n```\n```\npublic class Foo {}\n```\n```nosuchlang\nint x;\n```\n");
        for (var line : f.lines()) {
            if (line.toString().startsWith("```")) {
                continue;
            }
            assertFalse(colored(line), line.toString());
        }
    }

    @Test
    void untaggedBlockThatLooksLikeDiffUsesDiffRendering() {
        var f = fence(CONFIG);
        f.append("```\n--- a/Foo.java\n+++ b/Foo.java\n@@ -1,1 +1,1 @@\n-int a;\n+int b;\n```\n");
        var lines = f.lines();
        assertTrue(ChatTestSupport.bold(lines.get(1), 0), "header is bold");
        assertTrue(ChatTestSupport.tinted(lines.get(5)), "added line has a tint");
        assertTrue(ChatTestSupport.tinted(lines.get(4)), "removed line has a tint");
    }

    @Test
    void diffTagUsesDiffRendering() {
        var f = fence(CONFIG);
        f.append("```diff\n@@ -1 +1 @@\n-a\n+b\n```\n");
        assertTrue(ChatTestSupport.tinted(f.lines().get(3)));
    }

    @Test
    void textKeepsNoEscapeCodes() {
        var f = fence(CONFIG);
        var source = "x\n```java\npublic class Foo {}\n```\n";
        f.append(source);
        assertEquals(source, f.text());
        assertFalse(f.text().contains("\u001b"));
        for (var line : f.lines()) {
            assertFalse(line.toString().contains("\u001b"));
        }
    }

    @Test
    void noColorAndSwitchesTurnCodeColorsOff() {
        var source = "```java\npublic class Foo {}\n```\n";
        for (var config : new ChatHighlightConfig[] {CONFIG.withNoColor(true), CONFIG.withEnabled(false),
                CONFIG.withFences(false), CONFIG.withEditorSyntaxEnabled(false)}) {
            var f = fence(config);
            f.append(source);
            assertFalse(colored(f.lines().get(1)), config.toString());
        }
    }

    @Test
    void blockOverTheLimitGetsANote() {
        var f = fence(CONFIG.withMaxLines(2));
        f.append("```java\nint a;\nint b;\nint c;\nint d;\n```\n");
        var lines = f.lines().stream().map(Object::toString).toList();
        assertTrue(lines.contains("[Colors stop after 2 lines.]"), lines.toString());
        assertTrue(colored(f.lines().get(2)));
        assertFalse(colored(f.lines().get(5)));
    }

    @Test
    void tildeFencesAndLongerClosingFencesWork() {
        var f = fence(CONFIG);
        f.append("~~~java\nint a;\n~~~~\nplain\n");
        assertTrue(colored(f.lines().get(1)));
        assertFalse(colored(f.lines().get(3)));
    }
}
